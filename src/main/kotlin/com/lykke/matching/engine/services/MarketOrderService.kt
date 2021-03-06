package com.lykke.matching.engine.services

import com.lykke.matching.engine.cache.WalletCredentialsCache
import com.lykke.matching.engine.daos.LimitOrder
import com.lykke.matching.engine.daos.LkkTrade
import com.lykke.matching.engine.daos.MarketOrder
import com.lykke.matching.engine.daos.MatchingData
import com.lykke.matching.engine.daos.OrderTradesLink
import com.lykke.matching.engine.daos.Trade
import com.lykke.matching.engine.daos.WalletOperation
import com.lykke.matching.engine.daos.bitcoin.ClientOrderPair
import com.lykke.matching.engine.daos.bitcoin.ClientTradePair
import com.lykke.matching.engine.daos.bitcoin.Orders
import com.lykke.matching.engine.database.BackOfficeDatabaseAccessor
import com.lykke.matching.engine.database.MarketOrderDatabaseAccessor
import com.lykke.matching.engine.greaterThan
import com.lykke.matching.engine.holders.AssetsHolder
import com.lykke.matching.engine.holders.AssetsPairsHolder
import com.lykke.matching.engine.holders.BalancesHolder
import com.lykke.matching.engine.logging.AMOUNT
import com.lykke.matching.engine.logging.ASSET_PAIR
import com.lykke.matching.engine.logging.CLIENT_ID
import com.lykke.matching.engine.logging.ID
import com.lykke.matching.engine.logging.KeyValue
import com.lykke.matching.engine.logging.Line
import com.lykke.matching.engine.logging.ME_MARKET_ORDER
import com.lykke.matching.engine.logging.MetricsLogger
import com.lykke.matching.engine.logging.STATUS
import com.lykke.matching.engine.logging.STRAIGHT
import com.lykke.matching.engine.logging.TIMESTAMP
import com.lykke.matching.engine.logging.UID
import com.lykke.matching.engine.messages.MessageWrapper
import com.lykke.matching.engine.messages.ProtocolMessages
import com.lykke.matching.engine.order.OrderStatus.Dust
import com.lykke.matching.engine.order.OrderStatus.Matched
import com.lykke.matching.engine.order.OrderStatus.NoLiquidity
import com.lykke.matching.engine.order.OrderStatus.NotEnoughFunds
import com.lykke.matching.engine.order.OrderStatus.Processing
import com.lykke.matching.engine.order.OrderStatus.UnknownAsset
import com.lykke.matching.engine.outgoing.messages.JsonSerializable
import com.lykke.matching.engine.outgoing.messages.OrderBook
import com.lykke.matching.engine.queue.transaction.Swap
import com.lykke.matching.engine.queue.transaction.Transaction
import com.lykke.matching.engine.round
import com.lykke.matching.engine.utils.RoundingUtils
import org.apache.log4j.Logger
import java.time.LocalDateTime
import java.util.ArrayList
import java.util.Date
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.PriorityBlockingQueue

class MarketOrderService(private val backOfficeDatabaseAccessor: BackOfficeDatabaseAccessor,
                         private val marketOrderDatabaseAccessor: MarketOrderDatabaseAccessor,
                         private val genericLimitOrderService: GenericLimitOrderService,
                         private val assetsHolder: AssetsHolder,
                         private val assetsPairsHolder: AssetsPairsHolder,
                         private val balancesHolder: BalancesHolder,
                         private val backendQueue: BlockingQueue<Transaction>,
                         private val orderBookQueue: BlockingQueue<OrderBook>,
                         private val rabbitOrderBookQueue: BlockingQueue<JsonSerializable>,
                         private val walletCredentialsCache: WalletCredentialsCache,
                         private val lkkTradesHistoryEnabled: Boolean,
                         private val lkkTradesAssets: Set<String>,
                         private val rabbitSwapQueue: BlockingQueue<JsonSerializable>,
                         private val sendSwapToRabbit: Boolean): AbsractService<ProtocolMessages.MarketOrder> {

    companion object {
        val LOGGER = Logger.getLogger(MarketOrderService::class.java.name)
        val METRICS_LOGGER = MetricsLogger.getLogger()
    }

    private var messagesCount: Long = 0

    override fun processMessage(messageWrapper: MessageWrapper) {
        val message = parse(messageWrapper.byteArray)
        LOGGER.debug("Got market order id: ${message.uid}, client: ${message.clientId}, asset: ${message.assetPairId}, volume: ${RoundingUtils.roundForPrint(message.volume)}, straight: ${message.straight}")

        val uid = UUID.randomUUID().toString()
        val order = MarketOrder(uid, message.uid.toString(), message.assetPairId, message.clientId, message.volume, null,
        Processing.name, Date(message.timestamp), Date(), null, message.straight)

        try {
            assetsPairsHolder.getAssetPair(order.assetPairId)
        } catch (e: Exception) {
            order.status = UnknownAsset.name
            marketOrderDatabaseAccessor.addMarketOrder(order)
            LOGGER.info("Unknown asset: ${order.assetPairId}")
            messageWrapper.writeResponse(ProtocolMessages.Response.newBuilder().setUid(message.uid).setRecordId(order.id).build())
            METRICS_LOGGER.log(getMetricLine(order.externalId, order))
            METRICS_LOGGER.log(KeyValue(ME_MARKET_ORDER, (++messagesCount).toString()))
            return
        }

        val orderBook = genericLimitOrderService.getOrderBook(order.assetPairId).getCopyOfOrderBook(!order.isBuySide())
        if (orderBook.size == 0) {
            order.status = NoLiquidity.name
            marketOrderDatabaseAccessor.addMarketOrder(order)
            LOGGER.info("No liquidity, no orders in order book, for market order id: ${order.id}}, client: ${order.clientId}, asset: ${order.assetPairId}, volume: ${RoundingUtils.roundForPrint(order.volume)}, straight: ${order.straight}")
            messageWrapper.writeResponse(ProtocolMessages.Response.newBuilder().setUid(message.uid).setRecordId(order.id).build())
            return
        }

        match(order, orderBook)
        messageWrapper.writeResponse(ProtocolMessages.Response.newBuilder().setUid(message.uid).setRecordId(order.id).build())
        METRICS_LOGGER.log(getMetricLine(order.externalId, order))
        METRICS_LOGGER.log(KeyValue(ME_MARKET_ORDER, (++messagesCount).toString()))
    }

    private fun parse(array: ByteArray): ProtocolMessages.MarketOrder {
        return ProtocolMessages.MarketOrder.parseFrom(array)
    }

    private fun getCrossVolume(volume: Double, straight: Boolean, price: Double): Double {
        return if (straight) volume else volume / price
    }

    private fun getVolume(volume: Double, straight: Boolean, price: Double): Double {
        return if (straight) volume else volume * price
    }

    private fun match(marketOrder: MarketOrder, orderBook: PriorityBlockingQueue<LimitOrder>) {
        var remainingVolume = marketOrder.getAbsVolume()
        val matchedOrders = LinkedList<LimitOrder>()
        val skipLimitOrders = HashSet<LimitOrder>()
        val cancelledLimitOrders = HashSet<LimitOrder>()

        var totalLimitPrice = 0.0
        var totalMarketVolume = 0.0
        val limitBalances = HashMap<String, Double>()

        val assetPair = assetsPairsHolder.getAssetPair(marketOrder.assetPairId)
        while (remainingVolume.greaterThan(0.0) && orderBook.size > 0) {
            val limitOrder = orderBook.poll()
            val limitRemainingVolume = limitOrder.getAbsRemainingVolume()
            val marketRemainingVolume = getCrossVolume(remainingVolume, marketOrder.straight, limitOrder.price)
            val volume = if (marketRemainingVolume >= limitRemainingVolume) limitRemainingVolume else marketRemainingVolume
            val limitBalance = limitBalances[limitOrder.clientId] ?: balancesHolder.getBalance(limitOrder.clientId, if (limitOrder.isBuySide()) assetPair.quotingAssetId else assetPair.baseAssetId)
            val limitVolume = Math.abs(if (limitOrder.isBuySide()) volume * limitOrder.price else volume)
            if (marketOrder.clientId == limitOrder.clientId) {
                skipLimitOrders.add(limitOrder)
            } else if (genericLimitOrderService.isEnoughFunds(limitOrder, volume) && limitBalance >= limitVolume) {
                matchedOrders.add(limitOrder)
                remainingVolume -= getVolume(volume, marketOrder.straight, limitOrder.price)
                totalMarketVolume += volume
                totalLimitPrice += volume * limitOrder.price
                limitBalances[limitOrder.clientId] = limitBalance - limitVolume
            } else {
                cancelledLimitOrders.add(limitOrder)
            }
        }

        if (remainingVolume.greaterThan(0.0)) {
            marketOrder.status = NoLiquidity.name
            marketOrderDatabaseAccessor.addMarketOrder(marketOrder)
            LOGGER.info("No liquidity, not enough funds on limit orders, for market order id: ${marketOrder.id}}, client: ${marketOrder.clientId}, asset: ${marketOrder.assetPairId}, volume: ${RoundingUtils.roundForPrint(marketOrder.volume)} | Unfilled: ${RoundingUtils.roundForPrint(remainingVolume)}")
            return
        }

        if (!isEnoughFunds(marketOrder, if(marketOrder.isBuySide()) totalLimitPrice else totalMarketVolume )) {
            marketOrder.status = NotEnoughFunds.name
            marketOrderDatabaseAccessor.addMarketOrder(marketOrder)
            LOGGER.info("Not enough funds for market order id: ${marketOrder.id}}, client: ${marketOrder.clientId}, asset: ${marketOrder.assetPairId}, volume: ${RoundingUtils.roundForPrint(marketOrder.volume)}")
            return
        }

        val isMarketBuy = marketOrder.isBuySide()
        val marketAsset = assetsHolder.getAsset(if (isMarketBuy) assetPair.quotingAssetId else assetPair.baseAssetId)
        val limitAsset = assetsHolder.getAsset(if (isMarketBuy) assetPair.baseAssetId else assetPair.quotingAssetId)

        if (marketAsset.dustLimit!= null && marketAsset.dustLimit > 0.0) {
            val balance = balancesHolder.getBalance(marketOrder.clientId, marketAsset.assetId)
            if (marketOrder.straight && isMarketBuy) {
                val lastLimitOrder = matchedOrders.last
                if (balance - Math.abs(totalLimitPrice) < marketAsset.dustLimit) {
                    marketOrder.dustSize = RoundingUtils.parseDouble((balance - Math.abs(totalLimitPrice)) * lastLimitOrder.price, marketAsset.accuracy).toDouble()
                    marketOrder.volume = if (marketOrder.isOrigBuySide()) balance else - balance
                }
            } else if (marketOrder.straight && !isMarketBuy) {
                if (balance - Math.abs(marketOrder.volume) < marketAsset.dustLimit) {
                    marketOrder.dustSize = RoundingUtils.parseDouble(balance - Math.abs(marketOrder.volume), marketAsset.accuracy).toDouble()
                    marketOrder.volume = RoundingUtils.parseDouble(Math.signum(marketOrder.volume) * (Math.abs(marketOrder.volume) + marketOrder.dustSize!!), marketAsset.accuracy).toDouble()
                }
            } else if (!marketOrder.straight) {
                val marketVolume = if(marketOrder.isBuySide()) totalLimitPrice else totalMarketVolume
                val lastLimitOrder = matchedOrders.last
                if (balance - Math.abs(marketVolume) < marketAsset.dustLimit) {
                    marketOrder.dustSize = RoundingUtils.parseDouble((balance - Math.abs(marketVolume)) * lastLimitOrder.price, assetPair.accuracy).toDouble()
                    marketOrder.volume = RoundingUtils.parseDouble(Math.signum(marketOrder.volume) * (Math.abs(marketOrder.volume) + marketOrder.dustSize!!), assetPair.accuracy).toDouble()
                }
            }

//            if (marketOrder.straight) {
//                val marketVolume = if (!isMarketBuy) totalLimitPrice else totalMarketVolume
//                if (holders - Math.abs(marketVolume) < marketAsset.dustLimit) {
//                    marketOrder.dustSize = RoundingUtils.parseDouble(holders - Math.abs(marketVolume), marketAsset.accuracy).toDouble()
//                    marketOrder.volume = if (marketOrder.isOrigBuySide) holders else - holders
//                }
//            } else {
//                val marketVolume = if(marketOrder.isBuySide) totalLimitPrice else totalMarketVolume
//                val lastLimitOrder = matchedOrders.last
//                if (holders - Math.abs(marketVolume) < marketAsset.dustLimit) {
//                    marketOrder.dustSize = RoundingUtils.parseDouble((holders - Math.abs(marketVolume)) * lastLimitOrder.price, assetPair.accuracy).toDouble()
//                    marketOrder.volume = RoundingUtils.parseDouble(marketOrder.volume + marketOrder.dustSize, assetPair.accuracy).toDouble()
//                }
//            }
        }

        remainingVolume = marketOrder.getAbsVolume()
        totalLimitPrice = 0.0
        var totalLimitVolume = 0.0
        totalMarketVolume = 0.0
        val now = Date()

        val completedLimitOrders = LinkedList<LimitOrder>()
        var uncompletedLimitOrder: LimitOrder? = null
        val marketTrades = LinkedList<Trade>()
        val limitTrades = LinkedList<Trade>()
        val lkkTrades = LinkedList<LkkTrade>()
        val cashMovements = LinkedList<WalletOperation>()
        val bitcoinTransactions = LinkedList<Transaction>()
        val matchingData = LinkedList<MatchingData>()
        val orderTradesLinks = LinkedList<OrderTradesLink>()

        var marketBalance = balancesHolder.getBalance(marketOrder.clientId, if (marketOrder.isBuySide()) assetPair.quotingAssetId else assetPair.baseAssetId)

        val rabbitTrade = com.lykke.matching.engine.outgoing.messages.Trade(marketOrder.externalId, now)

        matchedOrders.forEachIndexed { index, limitOrder ->
            val limitRemainingVolume = limitOrder.getAbsRemainingVolume()
            val marketRemainingVolume = getCrossVolume(remainingVolume, marketOrder.straight, limitOrder.price)
            val volume = if (marketRemainingVolume >= limitRemainingVolume) limitRemainingVolume else marketRemainingVolume

            var marketRoundedVolume = RoundingUtils.round(if (isMarketBuy) volume else -volume, assetsHolder.getAsset(assetPair.baseAssetId).accuracy, marketOrder.isOrigBuySide())
            var oppositeRoundedVolume = RoundingUtils.round(if (isMarketBuy) -limitOrder.price * volume else limitOrder.price * volume, assetsHolder.getAsset(assetPair.quotingAssetId).accuracy, marketOrder.isBuySide())

            LOGGER.info("Matching with limit order ${limitOrder.id}, price ${limitOrder.price}, " +
                    "marketVolume ${RoundingUtils.roundForPrint(if (isMarketBuy) oppositeRoundedVolume else marketRoundedVolume)}, " +
                    "limitVolume ${RoundingUtils.roundForPrint(if (isMarketBuy) marketRoundedVolume else oppositeRoundedVolume)}")

            if ((!marketOrder.straight) && (index == matchedOrders.size - 1)) {
                oppositeRoundedVolume = Math.signum(marketOrder.volume) * (RoundingUtils.round(Math.abs(marketOrder.volume), assetsHolder.getAsset(assetPair.quotingAssetId).accuracy, marketOrder.isBuySide()) - Math.abs(totalLimitVolume))
                marketRoundedVolume = RoundingUtils.round(- oppositeRoundedVolume / limitOrder.price, assetsHolder.getAsset(assetPair.baseAssetId).accuracy, marketOrder.isOrigBuySide())
                LOGGER.debug("Rounding last matched limit order trade: ${RoundingUtils.roundForPrint(marketRoundedVolume)}")
            }

            matchingData.add(MatchingData(marketOrder.id, limitOrder.id, marketRoundedVolume))
            matchingData.add(MatchingData(limitOrder.id, marketOrder.id, oppositeRoundedVolume))

            val clientTradePairs = ArrayList<ClientTradePair>()

            val marketMultisig = walletCredentialsCache.getWalletCredentials(marketOrder.clientId)!!.multisig
            val limitMultisig = walletCredentialsCache.getWalletCredentials(limitOrder.clientId)!!.multisig

            //in case of non-straight orders, avoid negative holders due to rounding of asset pair
            if (marketAsset.dustLimit != null && marketAsset.dustLimit > 0 && marketBalance > 0.0 && marketBalance - Math.abs(if (isMarketBuy) oppositeRoundedVolume else marketRoundedVolume) < marketAsset.dustLimit) {
                if (isMarketBuy) {
                    oppositeRoundedVolume = Math.signum(oppositeRoundedVolume) * marketBalance
                    LOGGER.debug("Adjusting volume due to dust: ${RoundingUtils.roundForPrint(oppositeRoundedVolume)}")
                } else {
                    marketRoundedVolume = Math.signum(marketRoundedVolume) * marketBalance
                    LOGGER.debug("Adjusting volume due to dust: ${RoundingUtils.roundForPrint(marketRoundedVolume)}")
                }
            }

            if (marketOrder.isBuySide()) {
                if (marketBalance < Math.abs(oppositeRoundedVolume)) {
                    oppositeRoundedVolume = Math.signum(oppositeRoundedVolume) * marketBalance
                    LOGGER.debug("Adjusting volume due to low balance and rounding: ${RoundingUtils.roundForPrint(oppositeRoundedVolume)}")
                }
            } else {
                if (marketBalance < Math.abs(marketRoundedVolume)) {
                    marketRoundedVolume = Math.signum(marketRoundedVolume) * marketBalance
                    LOGGER.debug("Adjusting volume due to low balance and rounding: ${RoundingUtils.roundForPrint(marketRoundedVolume)}")
                }
            }

            LOGGER.debug("Corrected volumes: " +
                    "marketVolume ${RoundingUtils.roundForPrint(if (isMarketBuy) oppositeRoundedVolume else marketRoundedVolume)}, " +
                    "limitVolume ${RoundingUtils.roundForPrint(if (isMarketBuy) marketRoundedVolume else oppositeRoundedVolume)}")

            //check dust
            if (marketAsset.dustLimit != null && Math.abs(if (isMarketBuy) oppositeRoundedVolume else marketRoundedVolume) < marketAsset.dustLimit) {
                marketOrder.status = Dust.name
                marketOrderDatabaseAccessor.addMarketOrder(marketOrder)
                LOGGER.info("Market volume ${RoundingUtils.roundForPrint(if (isMarketBuy) oppositeRoundedVolume else marketRoundedVolume)} is less than dust ${RoundingUtils.roundForPrint(marketAsset.dustLimit)}. id: ${marketOrder.id}}, client: ${marketOrder.clientId}, asset: ${marketOrder.assetPairId}, volume: ${RoundingUtils.roundForPrint(marketOrder.volume)}")
                return
            }
            if (limitAsset.dustLimit != null && Math.abs(if (isMarketBuy) marketRoundedVolume else oppositeRoundedVolume) < limitAsset.dustLimit) {
                marketOrder.status = Dust.name
                marketOrderDatabaseAccessor.addMarketOrder(marketOrder)
                LOGGER.info("Limit volume ${RoundingUtils.roundForPrint(if (isMarketBuy) marketRoundedVolume else oppositeRoundedVolume)} is less than dust ${RoundingUtils.roundForPrint(limitAsset.dustLimit)}. id: ${marketOrder.id}}, client: ${marketOrder.clientId}, asset: ${marketOrder.assetPairId}, volume: ${RoundingUtils.roundForPrint(marketOrder.volume)}")
                return
            }

            var uid = Trade.generateId(now)
            var trade = Trade(marketOrder.clientId, uid, marketMultisig,
                    assetPair.baseAssetId, now, limitOrder.id,
                    marketOrder.id, marketRoundedVolume, limitOrder.price,
                    if (marketRoundedVolume > 0.0) limitMultisig else marketMultisig,
                    if (marketRoundedVolume > 0.0) marketMultisig else limitMultisig)
            marketTrades.add(trade)
            orderTradesLinks.add(OrderTradesLink(marketOrder.id, uid))
            orderTradesLinks.add(OrderTradesLink(limitOrder.id, uid))
            cashMovements.add(WalletOperation(UUID.randomUUID().toString(), null, marketOrder.clientId,
                    assetPair.baseAssetId, now, marketRoundedVolume))
            clientTradePairs.add(ClientTradePair(marketOrder.clientId, uid))

            uid = Trade.generateId(now)
            trade = Trade(marketOrder.clientId, uid, marketMultisig,
                    assetPair.quotingAssetId, now, limitOrder.id,
                    marketOrder.id, oppositeRoundedVolume, limitOrder.price,
                    if (oppositeRoundedVolume > 0.0) limitMultisig else marketMultisig,
                    if (oppositeRoundedVolume > 0.0) marketMultisig else limitMultisig)
            marketTrades.add(trade)
            orderTradesLinks.add(OrderTradesLink(marketOrder.id, uid))
            orderTradesLinks.add(OrderTradesLink(limitOrder.id, uid))
            cashMovements.add(WalletOperation(UUID.randomUUID().toString(), null, marketOrder.clientId,
                    assetPair.quotingAssetId, now, oppositeRoundedVolume))
            clientTradePairs.add(ClientTradePair(marketOrder.clientId, uid))

            uid = Trade.generateId(now)
            trade = Trade(limitOrder.clientId, uid, limitMultisig,
                    assetPair.baseAssetId, now, limitOrder.id,
                    marketOrder.id, -marketRoundedVolume, limitOrder.price,
                    if (-marketRoundedVolume > 0.0) marketMultisig else limitMultisig,
                    if (-marketRoundedVolume > 0.0) limitMultisig else marketMultisig)
            limitTrades.add(trade)
            orderTradesLinks.add(OrderTradesLink(marketOrder.id, uid))
            orderTradesLinks.add(OrderTradesLink(limitOrder.id, uid))
            cashMovements.add(WalletOperation(UUID.randomUUID().toString(), null, limitOrder.clientId,
                    assetPair.baseAssetId, now, -marketRoundedVolume))
            clientTradePairs.add(ClientTradePair(limitOrder.clientId, uid))

            uid = Trade.generateId(now)
            trade = Trade(limitOrder.clientId, uid, limitMultisig,
                    assetPair.quotingAssetId, now, limitOrder.id,
                    marketOrder.id, -oppositeRoundedVolume, limitOrder.price,
                    if (-oppositeRoundedVolume > 0.0) marketMultisig else limitMultisig,
                    if (-oppositeRoundedVolume > 0.0) limitMultisig else marketMultisig)
            limitTrades.add(trade)
            orderTradesLinks.add(OrderTradesLink(marketOrder.id, uid))
            orderTradesLinks.add(OrderTradesLink(limitOrder.id, uid))
            cashMovements.add(WalletOperation(UUID.randomUUID().toString(), null, limitOrder.clientId,
                    assetPair.quotingAssetId, now, -oppositeRoundedVolume))
            clientTradePairs.add(ClientTradePair(limitOrder.clientId, uid))

            if (marketRemainingVolume >= limitRemainingVolume) {
                if (lkkTradesHistoryEnabled && (lkkTradesAssets.contains(assetPair.baseAssetId) || lkkTradesAssets.contains(assetPair.quotingAssetId))) {
                    lkkTrades.add(LkkTrade(limitOrder.assetPairId, limitOrder.price, limitOrder.remainingVolume, now))
                }
                limitOrder.remainingVolume = 0.0
                limitOrder.status = Matched.name
                completedLimitOrders.add(limitOrder)
            } else {
                if (lkkTradesHistoryEnabled && (lkkTradesAssets.contains(assetPair.baseAssetId) || lkkTradesAssets.contains(assetPair.quotingAssetId))) {
                    lkkTrades.add(LkkTrade(limitOrder.assetPairId, limitOrder.price, -marketRoundedVolume, now))
                }
                limitOrder.remainingVolume = RoundingUtils.parseDouble(limitOrder.remainingVolume + marketRoundedVolume, assetsPairsHolder.getAssetPair(limitOrder.assetPairId).accuracy).toDouble()
                limitOrder.status = Processing.name
                uncompletedLimitOrder = limitOrder
            }

            remainingVolume = RoundingUtils.round(remainingVolume - getVolume(Math.abs(marketRoundedVolume), marketOrder.straight, limitOrder.price), assetsHolder.getAsset(assetPair.baseAssetId).accuracy, marketOrder.isOrigBuySide())
            limitOrder.lastMatchTime = now

            bitcoinTransactions.add(Swap(clientId1 = marketOrder.clientId, Amount1 = Math.abs(if (isMarketBuy) oppositeRoundedVolume else marketRoundedVolume), origAsset1 = marketAsset.assetId,
                                         clientId2 = limitOrder.clientId, Amount2 = Math.abs(if (isMarketBuy) marketRoundedVolume else oppositeRoundedVolume), origAsset2 = limitAsset.assetId,
                                         orders = Orders(ClientOrderPair(marketOrder.clientId, marketOrder.id), ClientOrderPair(limitOrder.clientId, limitOrder.id),
                                                 clientTradePairs.toTypedArray())))

            rabbitTrade.swaps.add(com.lykke.matching.engine.outgoing.messages.Swap(
                    marketOrder.clientId, Math.abs(if (isMarketBuy) oppositeRoundedVolume else marketRoundedVolume).round(marketAsset.accuracy), marketAsset.assetId,
                    limitOrder.clientId, Math.abs(if (isMarketBuy) marketRoundedVolume else oppositeRoundedVolume).round(limitAsset.accuracy), limitAsset.assetId,
                    Orders(ClientOrderPair(marketOrder.clientId, marketOrder.id), ClientOrderPair(limitOrder.clientId, limitOrder.id), clientTradePairs.toTypedArray())))

            totalMarketVolume += volume
            totalLimitPrice += volume * limitOrder.price
            totalLimitVolume += Math.abs(if (marketOrder.straight) marketRoundedVolume else oppositeRoundedVolume)
            marketBalance = RoundingUtils.parseDouble(marketBalance - Math.abs(if (isMarketBuy) oppositeRoundedVolume else marketRoundedVolume), marketAsset.accuracy).toDouble()
        }

        marketOrder.status = Matched.name
        marketOrder.matchedAt = now
        marketOrder.price = RoundingUtils.round(if (marketOrder.straight) totalLimitPrice / marketOrder.getAbsVolume() else marketOrder.getAbsVolume() / totalMarketVolume
                , assetsPairsHolder.getAssetPair(marketOrder.assetPairId).accuracy, marketOrder.isOrigBuySide())
        marketOrderDatabaseAccessor.addMarketOrder(marketOrder)
        marketOrderDatabaseAccessor.addMarketOrderWithGeneratedRowId(marketOrder)

        marketOrderDatabaseAccessor.addTrades(marketTrades)
        marketOrderDatabaseAccessor.addTrades(limitTrades)
        marketOrderDatabaseAccessor.addMatchingData(matchingData)
        marketOrderDatabaseAccessor.addOrderTradesLinks(orderTradesLinks)
        marketOrderDatabaseAccessor.addLkkTrades(lkkTrades)

        balancesHolder.processWalletOperations(cashMovements)

        genericLimitOrderService.moveOrdersToDone(completedLimitOrders)
        cancelledLimitOrders.forEach { limitOrder ->
            limitOrder.status = NotEnoughFunds.name
        }
        genericLimitOrderService.moveOrdersToDone(ArrayList<LimitOrder>(cancelledLimitOrders))

        skipLimitOrders.forEach { orderBook.put(it) }

        if (uncompletedLimitOrder != null) {
            genericLimitOrderService.updateLimitOrder(uncompletedLimitOrder as LimitOrder)
            orderBook.put(uncompletedLimitOrder)
        }

        genericLimitOrderService.setOrderBook(marketOrder.assetPairId, !marketOrder.isBuySide(), orderBook)

        if (sendSwapToRabbit) {
            rabbitSwapQueue.put(rabbitTrade)
        } else {
            bitcoinTransactions.forEach { backendQueue.put(it) }
        }

        val newOrderBook = OrderBook(marketOrder.assetPairId, !marketOrder.isBuySide(), now, genericLimitOrderService.getOrderBook(marketOrder.assetPairId).getCopyOfOrderBook(!marketOrder.isBuySide()))
        orderBookQueue.put(newOrderBook)
        rabbitOrderBookQueue.put(newOrderBook)

        LOGGER.info("Market order id: ${marketOrder.id}}, client: ${marketOrder.clientId}, asset: ${marketOrder.assetPairId}, volume: ${RoundingUtils.roundForPrint(marketOrder.volume)}, straight: ${marketOrder.straight} matched, price: ${RoundingUtils.roundForPrint(marketOrder.price)}")
    }

    fun isEnoughFunds(order: MarketOrder, totalPrice: Double): Boolean {
        val assetPair = assetsPairsHolder.getAssetPair(order.assetPairId)

        val asset = if (order.isBuySide()) assetPair.quotingAssetId else assetPair.baseAssetId
        val roundedPrice = RoundingUtils.round(totalPrice, assetsHolder.getAsset(asset).accuracy, true)

        LOGGER.debug("${order.clientId} $asset : ${RoundingUtils.roundForPrint(balancesHolder.getBalance(order.clientId, asset))} >= ${RoundingUtils.roundForPrint(roundedPrice)}")
        return balancesHolder.getBalance(order.clientId, asset) >= roundedPrice
    }

    fun getMetricLine(uid: String, order: MarketOrder): Line {
        return Line(ME_MARKET_ORDER, arrayOf(
                KeyValue(UID, uid),
                KeyValue(ID, order.id),
                KeyValue(TIMESTAMP, LocalDateTime.now().format(MetricsLogger.DATE_TIME_FORMATTER)),
                KeyValue(CLIENT_ID, order.clientId),
                KeyValue(ASSET_PAIR, order.assetPairId),
                KeyValue(AMOUNT, order.volume.toString()),
                KeyValue(STRAIGHT, order.straight.toString()),
                KeyValue(STATUS, order.status)
        ))
    }
}