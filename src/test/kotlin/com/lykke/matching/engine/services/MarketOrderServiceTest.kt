package com.lykke.matching.engine.services

import com.lykke.matching.engine.cache.WalletCredentialsCache
import com.lykke.matching.engine.daos.Asset
import com.lykke.matching.engine.daos.AssetPair
import com.lykke.matching.engine.daos.TradeInfo
import com.lykke.matching.engine.daos.WalletCredentials
import com.lykke.matching.engine.database.TestBackOfficeDatabaseAccessor
import com.lykke.matching.engine.database.TestLimitOrderDatabaseAccessor
import com.lykke.matching.engine.database.TestMarketOrderDatabaseAccessor
import com.lykke.matching.engine.database.TestWalletDatabaseAccessor
import com.lykke.matching.engine.database.buildWallet
import com.lykke.matching.engine.database.cache.AssetPairsCache
import com.lykke.matching.engine.database.cache.AssetsCache
import com.lykke.matching.engine.database.file.FileOrderBookDatabaseAccessor
import com.lykke.matching.engine.holders.AssetsHolder
import com.lykke.matching.engine.holders.AssetsPairsHolder
import com.lykke.matching.engine.holders.BalancesHolder
import com.lykke.matching.engine.notification.BalanceUpdateNotification
import com.lykke.matching.engine.notification.QuotesUpdate
import com.lykke.matching.engine.order.OrderStatus.InOrderBook
import com.lykke.matching.engine.order.OrderStatus.Matched
import com.lykke.matching.engine.order.OrderStatus.NoLiquidity
import com.lykke.matching.engine.order.OrderStatus.NotEnoughFunds
import com.lykke.matching.engine.order.OrderStatus.Processing
import com.lykke.matching.engine.outgoing.messages.JsonSerializable
import com.lykke.matching.engine.outgoing.messages.OrderBook
import com.lykke.matching.engine.queue.transaction.Swap
import com.lykke.matching.engine.queue.transaction.Transaction
import com.lykke.matching.engine.utils.MessageBuilder.Companion.buildLimitOrder
import com.lykke.matching.engine.utils.MessageBuilder.Companion.buildMarketOrder
import com.lykke.matching.engine.utils.MessageBuilder.Companion.buildMarketOrderWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertNotNull

class MarketOrderServiceTest {
    var testDatabaseAccessor = TestMarketOrderDatabaseAccessor()
    var testLimitDatabaseAccessor = TestLimitOrderDatabaseAccessor()
    var testWalletDatabaseAcessor = TestWalletDatabaseAccessor()
    var testBackOfficeDatabaseAcessor = TestBackOfficeDatabaseAccessor()
    val transactionQueue = LinkedBlockingQueue<Transaction>()
    val tradesInfoQueue = LinkedBlockingQueue<TradeInfo>()
    val orderBookQueue = LinkedBlockingQueue<OrderBook>()
    val rabbitOrderBookQueue = LinkedBlockingQueue<JsonSerializable>()
    val rabbitSwapQueue = LinkedBlockingQueue<JsonSerializable>()
    val quotesNotificationQueue = LinkedBlockingQueue<QuotesUpdate>()
    val walletCredentialsCache = WalletCredentialsCache(testBackOfficeDatabaseAcessor)

    val assetsHolder = AssetsHolder(AssetsCache(testBackOfficeDatabaseAcessor, 60000))
    val assetsPairsHolder = AssetsPairsHolder(AssetPairsCache(testWalletDatabaseAcessor, 60000))
    val balancesHolder = BalancesHolder(testWalletDatabaseAcessor, assetsHolder, LinkedBlockingQueue<BalanceUpdateNotification>())

    var limitOrderService = GenericLimitOrderService(false, testLimitDatabaseAccessor, FileOrderBookDatabaseAccessor(""), assetsPairsHolder, balancesHolder, tradesInfoQueue, quotesNotificationQueue)
    var service = MarketOrderService(testBackOfficeDatabaseAcessor, testDatabaseAccessor, limitOrderService, assetsHolder, assetsPairsHolder, balancesHolder, transactionQueue, orderBookQueue, rabbitOrderBookQueue, walletCredentialsCache, true, setOf("LKK"), rabbitSwapQueue, false)

    val DELTA = 1e-9

    @Before
    fun setUp() {
        testDatabaseAccessor.clear()
        testLimitDatabaseAccessor.clear()
        testWalletDatabaseAcessor.clear()
        transactionQueue.clear()
        tradesInfoQueue.clear()

        testBackOfficeDatabaseAcessor.addWalletCredentials(WalletCredentials("Client1", "Client1-Multisig"))
        testBackOfficeDatabaseAcessor.addWalletCredentials(WalletCredentials("Client2", "Client2-Multisig"))
        testBackOfficeDatabaseAcessor.addWalletCredentials(WalletCredentials("Client3", "Client3-Multisig"))
        testBackOfficeDatabaseAcessor.addWalletCredentials(WalletCredentials("Client4", "Client4-Multisig"))
        testBackOfficeDatabaseAcessor.addAsset(Asset("LKK", 0, "LKK", 0.0))
        testBackOfficeDatabaseAcessor.addAsset(Asset("SLR", 2, "SLR", 0.0))
        testBackOfficeDatabaseAcessor.addAsset(Asset("EUR", 2, "EUR", 0.0))
        testBackOfficeDatabaseAcessor.addAsset(Asset("GBP", 2, "GBP", 0.001))
        testBackOfficeDatabaseAcessor.addAsset(Asset("CHF", 2, "CHF", 0.0))
        testBackOfficeDatabaseAcessor.addAsset(Asset("USD", 2, "USD"))
        testBackOfficeDatabaseAcessor.addAsset(Asset("JPY", 2, "JPY"))
        testBackOfficeDatabaseAcessor.addAsset(Asset("BTC", 8, "BTC", 0.00001))
        testBackOfficeDatabaseAcessor.addAsset(Asset("BTC1", 8, "BTC1", 0.0000273))
        testWalletDatabaseAcessor.addAssetPair(AssetPair("EURUSD", "EUR", "USD", 5, 5))
        testWalletDatabaseAcessor.addAssetPair(AssetPair("EURJPY", "EUR", "JPY", 3, 6))
        testWalletDatabaseAcessor.addAssetPair(AssetPair("BTCUSD", "BTC", "USD", 8, 8))
        testWalletDatabaseAcessor.addAssetPair(AssetPair("BTCLKK", "BTC", "LKK", 6, 8))
        testWalletDatabaseAcessor.addAssetPair(AssetPair("BTC1USD", "BTC1", "USD", 3, 7))
        testWalletDatabaseAcessor.addAssetPair(AssetPair("BTCCHF", "BTC", "CHF", 3, 8))
        testWalletDatabaseAcessor.addAssetPair(AssetPair("SLRBTC", "SLR", "BTC", 8, 2))
        testWalletDatabaseAcessor.addAssetPair(AssetPair("LKKEUR", "LKK", "EUR", 5, 2))
        testWalletDatabaseAcessor.addAssetPair(AssetPair("LKKGBP", "LKK", "GBP", 5, 2))

        this.walletCredentialsCache.reloadCache()
    }

    @After
    fun tearDown() {
    }

    fun initServices() {
        limitOrderService = GenericLimitOrderService(false, testLimitDatabaseAccessor, FileOrderBookDatabaseAccessor(""), assetsPairsHolder, balancesHolder, tradesInfoQueue, quotesNotificationQueue)
        service = MarketOrderService(testBackOfficeDatabaseAcessor, testDatabaseAccessor, limitOrderService, assetsHolder, assetsPairsHolder, balancesHolder, transactionQueue, orderBookQueue, rabbitOrderBookQueue, walletCredentialsCache, true, setOf("LKK"), rabbitSwapQueue, false)
    }

    @Test
    fun testNoLiqudity() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.5, volume = 1000.0, clientId = "Client1"))
        initServices()

        service.processMessage(buildMarketOrderWrapper(buildMarketOrder()))
        assertEquals(NoLiquidity.name, testDatabaseAccessor.getLastOrder().status)
    }

    @Test
    fun testNotEnoughFundsClientOrder() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.6, volume = 1000.0, clientId = "Client1"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client1", "USD", 1000.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client1", "EUR", 1000.0))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.5, volume = 1000.0, clientId = "Client2"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client2", "USD", 1500.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client3", "EUR", 1500.0))
        initServices()

        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client3", assetId = "EURUSD", volume = -1000.0)))
        assertEquals(NotEnoughFunds.name, testLimitDatabaseAccessor.ordersDone.find { it.price == 1.6 }?.status)
    }

    @Test
    fun testNotEnoughFundsClientMultiOrder() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.6, volume = 1000.0, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.5, volume = 1000.0, clientId = "Client1"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client1", "USD", 2000.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client3", "EUR", 1500.0))
        initServices()

        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client3", assetId = "EURUSD", volume = -1500.0)))
        assertEquals(NoLiquidity.name, testDatabaseAccessor.getLastOrder().status)
    }

    @Test
    fun testNoLiqudityToFullyFill() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.5, volume = 1000.0, clientId = "Client2"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client2", "USD", 1500.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client2", "EUR", 2000.0))
        initServices()
        
        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client2", assetId = "EURUSD", volume = -2000.0)))
        assertEquals(NoLiquidity.name, testDatabaseAccessor.getLastOrder().status)
        assertEquals(InOrderBook.name, testLimitDatabaseAccessor.getLastOrder().status)
    }

    @Test
    fun testNotEnoughFundsMarketOrder() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.5, volume = 1000.0, clientId = "Client3"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client3", "USD", 1500.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "EUR", 900.0))
        initServices()
        
        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "EURUSD", volume = -1000.0)))
        assertEquals(NotEnoughFunds.name, testDatabaseAccessor.getLastOrder().status)
    }

    @Test
    fun testMatchOneToOne() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.5, volume = 1000.0, clientId = "Client3"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client3", "USD", 1500.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "EUR", 1000.0))
        initServices()
        
        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "EURUSD", volume = -1000.0)))

        val marketOrder = testDatabaseAccessor.orders.first()
        assertEquals(Matched.name, marketOrder.status)
        assertEquals(1.5, marketOrder.price!!, DELTA)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)
        assertEquals(8, testDatabaseAccessor.orderTradesLinks.size)

        assertEquals(0, testLimitDatabaseAccessor.orders.size)
        assertEquals(1, testLimitDatabaseAccessor.ordersDone.size)

        val limitOrder = testLimitDatabaseAccessor.ordersDone.first()
        assertEquals(Matched.name, limitOrder.status)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)

        assertEquals(1, testLimitDatabaseAccessor.ordersDone.size)
        assertNotNull(testLimitDatabaseAccessor.ordersDone.first())

        assertEquals(4, testDatabaseAccessor.trades.size)

        assertEquals(1000.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "EUR" }?.volume)
        assertEquals(-1500.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "USD" }?.volume)
        assertEquals(-1000.0, testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "EUR" }?.volume)
        assertEquals(1500.0, testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "USD" }?.volume)

        assertEquals(1000.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "EUR" && it.multisig == "Client3-Multisig" }?.volume)
        assertEquals(-1500.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "USD" && it.multisig == "Client3-Multisig" }?.volume)
        assertEquals(-1000.0, testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "EUR" && it.multisig == "Client4-Multisig" }?.volume)
        assertEquals(1500.0, testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "USD" && it.multisig == "Client4-Multisig" }?.volume)

        assertEquals("Client4-Multisig", testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "EUR" && it.volume == 1000.0}?.addressFrom)
        assertEquals("Client3-Multisig", testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "EUR" && it.volume == 1000.0}?.addressTo)
        assertEquals("Client3-Multisig", testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "USD" && it.volume == -1500.0}?.addressFrom)
        assertEquals("Client4-Multisig", testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "USD" && it.volume == -1500.0}?.addressTo)
        assertEquals("Client4-Multisig", testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "EUR" && it.volume == -1000.0}?.addressFrom)
        assertEquals("Client3-Multisig", testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "EUR" && it.volume == -1000.0}?.addressTo)
        assertEquals("Client3-Multisig", testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "USD" && it.volume == 1500.0}?.addressFrom)
        assertEquals("Client4-Multisig", testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "USD" && it.volume == 1500.0}?.addressTo)

        assertEquals(1000.0, testWalletDatabaseAcessor.getBalance("Client3", "EUR"), DELTA)
        assertEquals(0.0, testWalletDatabaseAcessor.getBalance("Client3", "USD"), DELTA)
        assertEquals(0.0, testWalletDatabaseAcessor.getBalance("Client4", "EUR"), DELTA)
        assertEquals(1500.0, testWalletDatabaseAcessor.getBalance("Client4", "USD"), DELTA)

        val swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(1000.0, swap.Amount1, DELTA)
        assertEquals("EUR", swap.origAsset1)
        assertEquals("Client3", swap.clientId2)
        assertEquals(1500.0, swap.Amount2, DELTA)
        assertEquals("USD", swap.origAsset2)
    }

    @Test
    fun testMatchOneToOneEURJPY() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "EURJPY", price = 122.512, volume = 1000000.0, clientId = "Client3"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "EURJPY", price = 122.524, volume = -1000000.0, clientId = "Client3"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client3", "JPY", 5000000.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client3", "EUR", 5000000.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "EUR", 0.1))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "JPY", 100.0))
        initServices()
        
        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "EURJPY", volume = 10.0, straight = false)))

        val marketOrder = testDatabaseAccessor.orders.first()
        assertEquals(Matched.name, marketOrder.status)
        assertEquals(122.512, marketOrder.price!!, DELTA)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)
        assertEquals(8, testDatabaseAccessor.orderTradesLinks.size)

        assertEquals(2, testLimitDatabaseAccessor.orders.size)
        assertEquals(0, testLimitDatabaseAccessor.ordersDone.size)

        assertEquals(0.09, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "EUR" }?.volume)
        assertEquals(-10.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "JPY" }?.volume)
        assertEquals(-0.09, testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "EUR" }?.volume)
        assertEquals(10.0, testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "JPY" }?.volume)

        assertEquals(5000000.09, testWalletDatabaseAcessor.getBalance("Client3", "EUR"), DELTA)
        assertEquals(4999990.0, testWalletDatabaseAcessor.getBalance("Client3", "JPY"), DELTA)
        assertEquals(0.01, testWalletDatabaseAcessor.getBalance("Client4", "EUR"), DELTA)
        assertEquals(110.0, testWalletDatabaseAcessor.getBalance("Client4", "JPY"), DELTA)

        val swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(0.09, swap.Amount1, DELTA)
        assertEquals("EUR", swap.origAsset1)
        assertEquals("Client3", swap.clientId2)
        assertEquals(10.0, swap.Amount2, DELTA)
        assertEquals("JPY", swap.origAsset2)
    }

    @Test
    fun testMatchOneToOneAfterNotEnoughFunds() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.5, volume = 1000.0, clientId = "Client3"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client3", "USD", 1500.0))
        initServices()
        
        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "EURUSD", volume = -1000.0)))

        var marketOrder = testDatabaseAccessor.orders.first()
        assertEquals(NotEnoughFunds.name, marketOrder.status)

        testDatabaseAccessor.orders.clear()

        balancesHolder.updateBalance("Client4", "EUR", 1000.0)
        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "EURUSD", volume = -1000.0)))

        marketOrder = testDatabaseAccessor.orders.first()
        assertEquals(Matched.name, marketOrder.status)
        assertEquals(1.5, marketOrder.price!!, DELTA)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)
        assertEquals(8, testDatabaseAccessor.orderTradesLinks.size)

        assertEquals(0, testLimitDatabaseAccessor.orders.size)
        assertEquals(1, testLimitDatabaseAccessor.ordersDone.size)

        val limitOrder = testLimitDatabaseAccessor.ordersDone.first()
        assertEquals(Matched.name, limitOrder.status)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)

        assertEquals(1, testLimitDatabaseAccessor.ordersDone.size)
        assertNotNull(testLimitDatabaseAccessor.ordersDone.first())

        assertEquals(1000.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "EUR" }?.volume)
        assertEquals(-1500.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "USD" }?.volume)
        assertEquals(-1000.0, testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "EUR" }?.volume)
        assertEquals(1500.0, testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "USD" }?.volume)

        assertEquals(1000.0, testWalletDatabaseAcessor.getBalance("Client3", "EUR"), DELTA)
        assertEquals(0.0, testWalletDatabaseAcessor.getBalance("Client3", "USD"), DELTA)
        assertEquals(0.0, testWalletDatabaseAcessor.getBalance("Client4", "EUR"), DELTA)
        assertEquals(1500.0, testWalletDatabaseAcessor.getBalance("Client4", "USD"), DELTA)

        val swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(1000.0, swap.Amount1, DELTA)
        assertEquals("EUR", swap.origAsset1)
        assertEquals("Client3", swap.clientId2)
        assertEquals(1500.0, swap.Amount2, DELTA)
        assertEquals("USD", swap.origAsset2)
    }

    @Test
    fun testMatchOneToMany() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.5, volume = 100.0, clientId = "Client3"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.4, volume = 1000.0, clientId = "Client1"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client1", "USD", 1260.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client3", "USD", 150.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "EUR", 1000.0))
        initServices()
        
        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "EURUSD", volume = -1000.0)))

        val marketOrder = testDatabaseAccessor.orders.first()
        assertEquals(Matched.name, marketOrder.status)
        assertEquals(1.41, marketOrder.price!!, DELTA)
        assertEquals(2, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)
        assertEquals(16, testDatabaseAccessor.orderTradesLinks.size)

        assertEquals(1, testLimitDatabaseAccessor.orders.size)
        assertEquals(1, testLimitDatabaseAccessor.ordersDone.size)

        val activeLimitOrder = testLimitDatabaseAccessor.orders.first()
        assertEquals(100.0, activeLimitOrder.remainingVolume, DELTA)
        assertEquals(Processing.name, activeLimitOrder.status)

        val limitOrder = testLimitDatabaseAccessor.ordersDone.first()
        assertEquals(Matched.name, limitOrder.status)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == limitOrder.id }.size)

        assertEquals(100.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "EUR" }?.volume)
        assertEquals(-150.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "USD" }?.volume)
        assertEquals(900.0, testDatabaseAccessor.trades.find { it.clientId == "Client1" && it.assetId == "EUR" }?.volume)
        assertEquals(-1260.0, testDatabaseAccessor.trades.find { it.clientId == "Client1" && it.assetId == "USD" }?.volume)
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "EUR" && it.volume == -100.0})
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "USD" && it.volume == 150.0 })
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "EUR" && it.volume == -900.0})
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "USD" && it.volume == 1260.0 })

        assertEquals(100.0, testWalletDatabaseAcessor.getBalance("Client3", "EUR"), DELTA)
        assertEquals(0.0, testWalletDatabaseAcessor.getBalance("Client3", "USD"), DELTA)
        assertEquals(900.0, testWalletDatabaseAcessor.getBalance("Client1", "EUR"), DELTA)
        assertEquals(0.0, testWalletDatabaseAcessor.getBalance("Client1", "USD"), DELTA)
        assertEquals(0.0, testWalletDatabaseAcessor.getBalance("Client4", "EUR"), DELTA)
        assertEquals(1410.0, testWalletDatabaseAcessor.getBalance("Client4", "USD"), DELTA)

        var swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(100.0, swap.Amount1, DELTA)
        assertEquals("EUR", swap.origAsset1)
        assertEquals("Client3", swap.clientId2)
        assertEquals(150.0, swap.Amount2, DELTA)
        assertEquals("USD", swap.origAsset2)

        swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(900.0, swap.Amount1, DELTA)
        assertEquals("EUR", swap.origAsset1)
        assertEquals("Client1", swap.clientId2)
        assertEquals(1260.0, swap.Amount2, DELTA)
        assertEquals("USD", swap.origAsset2)
    }

    @Test
    fun testMatchOneToMany2016Nov10() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "LKKEUR", price = 0.04412, volume = -20000.0, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "LKKEUR", price = 0.04421, volume = -20000.0, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "LKKEUR", price = 0.04431, volume = -20000.0, clientId = "Client1"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client1", "LKK", 6569074.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "EUR", 7500.02))
        initServices()
        
        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "LKKEUR", volume = 50000.0)))

        val marketOrder = testDatabaseAccessor.orders.first()
        assertEquals(Matched.name, marketOrder.status)
        assertEquals(0.0442, marketOrder.price!!, DELTA)
        assertEquals(3, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)
        assertEquals(24, testDatabaseAccessor.orderTradesLinks.size)

        assertEquals(1, testLimitDatabaseAccessor.orders.size)
        assertEquals(2, testLimitDatabaseAccessor.ordersDone.size)

        val activeLimitOrder = testLimitDatabaseAccessor.orders.first()
        assertEquals(-10000.0, activeLimitOrder.remainingVolume, DELTA)
        assertEquals(Processing.name, activeLimitOrder.status)

        val limitOrder = testLimitDatabaseAccessor.ordersDone.first()
        assertEquals(Matched.name, limitOrder.status)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == limitOrder.id }.size)

        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client1" && it.assetId == "EUR" && it.volume == 882.4})
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client1" && it.assetId == "EUR" && it.volume == 884.2})
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client1" && it.assetId == "EUR" && it.volume == 443.1})
        assertEquals(2, testDatabaseAccessor.trades.filter { it.clientId == "Client4" && it.assetId == "LKK" && it.volume == 20000.0}.size)
        assertEquals(1, testDatabaseAccessor.trades.filter { it.clientId == "Client4" && it.assetId == "LKK" && it.volume == 10000.0}.size)

        assertEquals(2209.7, testWalletDatabaseAcessor.getBalance("Client1", "EUR"), DELTA)
        assertEquals(6519074.0, testWalletDatabaseAcessor.getBalance("Client1", "LKK"), DELTA)
        assertEquals(5290.32, testWalletDatabaseAcessor.getBalance("Client4", "EUR"), DELTA)
        assertEquals(50000.0, testWalletDatabaseAcessor.getBalance("Client4", "LKK"), DELTA)

        var swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(882.4, swap.Amount1, DELTA)
        assertEquals("EUR", swap.origAsset1)
        assertEquals("Client1", swap.clientId2)
        assertEquals(20000.0, swap.Amount2, DELTA)
        assertEquals("LKK", swap.origAsset2)

        swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(884.2, swap.Amount1, DELTA)
        assertEquals("EUR", swap.origAsset1)
        assertEquals("Client1", swap.clientId2)
        assertEquals(20000.0, swap.Amount2, DELTA)
        assertEquals("LKK", swap.origAsset2)

        swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(443.1, swap.Amount1, DELTA)
        assertEquals("EUR", swap.origAsset1)
        assertEquals("Client1", swap.clientId2)
        assertEquals(10000.0, swap.Amount2, DELTA)
        assertEquals("LKK", swap.origAsset2)
    }

    @Test
    fun testMatchOneToMany2016Nov10_2() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "BTCLKK", price = 13611.625476, volume = 1.463935, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "BTCLKK", price = 13586.531910, volume = 1.463935, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "BTCLKK", price = 13561.438344, volume = 1.463935, clientId = "Client1"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client1", "LKK", 100000.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "BTC", 12.67565686))
        initServices()

        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "BTCLKK", volume = 50000.0, straight = false)))

        val marketOrder = testDatabaseAccessor.orders.first()
        assertEquals(Matched.name, marketOrder.status)
        assertEquals(13591.395424, marketOrder.price!!, DELTA)
        assertEquals(3, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)
        assertEquals(24, testDatabaseAccessor.orderTradesLinks.size)

        assertEquals(1, testLimitDatabaseAccessor.orders.size)
        assertEquals(2, testLimitDatabaseAccessor.ordersDone.size)

        val activeLimitOrder = testLimitDatabaseAccessor.orders.first()
        assertEquals(0.712908, activeLimitOrder.remainingVolume, DELTA)
        assertEquals(Processing.name, activeLimitOrder.status)

        val limitOrder = testLimitDatabaseAccessor.ordersDone.first()
        assertEquals(Matched.name, limitOrder.status)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == limitOrder.id }.size)

        assertEquals(2, testDatabaseAccessor.trades.filter { it.clientId == "Client1" && it.assetId == "BTC" && it.volume == 1.463935}.size)
        assertEquals(1, testDatabaseAccessor.trades.filter { it.clientId == "Client1" && it.assetId == "BTC" && it.volume == 0.75102654}.size)
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "LKK" && it.volume == 19926.0})
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "LKK" && it.volume == 19889.0})
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "LKK" && it.volume == 10185.0})

        assertEquals(3.67889654, testWalletDatabaseAcessor.getBalance("Client1", "BTC"), DELTA)
        assertEquals(50000.0, testWalletDatabaseAcessor.getBalance("Client1", "LKK"), DELTA)
        assertEquals(8.99676032, testWalletDatabaseAcessor.getBalance("Client4", "BTC"), DELTA)
        assertEquals(50000.0, testWalletDatabaseAcessor.getBalance("Client4", "LKK"), DELTA)

        var swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(1.463935, swap.Amount1, DELTA)
        assertEquals("BTC", swap.origAsset1)
        assertEquals("Client1", swap.clientId2)
        assertEquals(19926.0, swap.Amount2, DELTA)
        assertEquals("LKK", swap.origAsset2)

        swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(1.463935, swap.Amount1, DELTA)
        assertEquals("BTC", swap.origAsset1)
        assertEquals("Client1", swap.clientId2)
        assertEquals(19889.0, swap.Amount2, DELTA)
        assertEquals("LKK", swap.origAsset2)

        swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(0.75102654, swap.Amount1, DELTA)
        assertEquals("BTC", swap.origAsset1)
        assertEquals("Client1", swap.clientId2)
        assertEquals(10185.0, swap.Amount2, DELTA)
        assertEquals("LKK", swap.origAsset2)
    }

    @Test
    fun testMatchOneToMany2016Nov10_3() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "LKKGBP", price = 0.0385, volume = -20000.0, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "LKKGBP", price = 0.03859, volume = -20000.0, clientId = "Client1"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client1", "LKK", 100000.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "GBP", 982.78))
        initServices()

        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "LKKGBP", volume = -982.78, straight = false)))

        val marketOrder = testDatabaseAccessor.orders.first()
        assertEquals(Matched.name, marketOrder.status)
        assertEquals(0.03854, marketOrder.price!!, DELTA)
        assertEquals(2, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)
        assertEquals(16, testDatabaseAccessor.orderTradesLinks.size)

        assertEquals(1, testLimitDatabaseAccessor.orders.size)
        assertEquals(1, testLimitDatabaseAccessor.ordersDone.size)

        val activeLimitOrder = testLimitDatabaseAccessor.orders.first()
        assertEquals(-14487.0, activeLimitOrder.remainingVolume, DELTA)
        assertEquals(Processing.name, activeLimitOrder.status)

        val limitOrder = testLimitDatabaseAccessor.ordersDone.first()
        assertEquals(Matched.name, limitOrder.status)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == limitOrder.id }.size)

        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client1" && it.assetId == "GBP" && it.volume == 770.0})
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client1" && it.assetId == "GBP" && it.volume == 212.78})
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "LKK" && it.volume == 20000.0})
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "LKK" && it.volume == 5513.0})

        assertEquals(982.78, testWalletDatabaseAcessor.getBalance("Client1", "GBP"), DELTA)
        assertEquals(74487.0, testWalletDatabaseAcessor.getBalance("Client1", "LKK"), DELTA)
        assertEquals(0.0, testWalletDatabaseAcessor.getBalance("Client4", "GBP"), DELTA)
        assertEquals(25513.0, testWalletDatabaseAcessor.getBalance("Client4", "LKK"), DELTA)

        var swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(770.0, swap.Amount1, DELTA)
        assertEquals("GBP", swap.origAsset1)
        assertEquals("Client1", swap.clientId2)
        assertEquals(20000.0, swap.Amount2, DELTA)
        assertEquals("LKK", swap.origAsset2)

        swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(212.78, swap.Amount1, DELTA)
        assertEquals("GBP", swap.origAsset1)
        assertEquals("Client1", swap.clientId2)
        assertEquals(5513.0, swap.Amount2, DELTA)
        assertEquals("LKK", swap.origAsset2)
    }

    @Test
    fun testMatchOneToMany2016Dec12() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "SLRBTC", price = 0.00008826, volume = -4000.0, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "SLRBTC", price = 0.00008844, volume = -4000.0, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "SLRBTC", price = 0.00008861, volume = -4000.0, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "SLRBTC", price = 0.00008879, volume = -4000.0, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "SLRBTC", price = 0.00008897, volume = -4000.0, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "SLRBTC", price = 0.00008914, volume = -4000.0, clientId = "Client1"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "SLRBTC", price = 0.00008932, volume = -4000.0, clientId = "Client1"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client1", "SLR", 100000.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "BTC", 31.95294))
        initServices()

        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "SLRBTC", volume = 25000.0, straight = true)))

        assertEquals(2.21816, testWalletDatabaseAcessor.getBalance("Client1", "BTC"), DELTA)
        assertEquals(75000.0, testWalletDatabaseAcessor.getBalance("Client1", "SLR"), DELTA)
        assertEquals(29.73478, testWalletDatabaseAcessor.getBalance("Client4", "BTC"), DELTA)
        assertEquals(25000.0, testWalletDatabaseAcessor.getBalance("Client4", "SLR"), DELTA)
    }

    @Test
    fun testMatchOneToMany2016Dec12_2() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(assetId = "BTCCHF", price = 791.37, volume = 4000.0, clientId = "Client1"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client1", "CHF", 100000.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "BTC", 0.00036983))
        initServices()

        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "BTCCHF", volume = -0.00036983, straight = true)))

        assertEquals(0.00036983, testWalletDatabaseAcessor.getBalance("Client1", "BTC"), DELTA)
        assertEquals(99999.71, testWalletDatabaseAcessor.getBalance("Client1", "CHF"), DELTA)
        assertEquals(0.0, testWalletDatabaseAcessor.getBalance("Client4", "BTC"), DELTA)
        assertEquals(0.29, testWalletDatabaseAcessor.getBalance("Client4", "CHF"), DELTA)
    }

    @Test
    fun testNotStraight() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.5, volume = -500.0, assetId = "EURUSD", clientId = "Client3"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client3", "EUR", 500.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "USD", 750.0))
        initServices()

        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "EURUSD", volume = -750.0, straight = false)))

        val marketOrder = testDatabaseAccessor.orders.first()
        assertEquals(Matched.name, marketOrder.status)
        assertEquals(1.5, marketOrder.price!!, DELTA)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)
        assertEquals(8, testDatabaseAccessor.orderTradesLinks.size)

        assertEquals(0, testLimitDatabaseAccessor.orders.size)
        assertEquals(1, testLimitDatabaseAccessor.ordersDone.size)

        val limitOrder = testLimitDatabaseAccessor.ordersDone.first()
        assertEquals(Matched.name, limitOrder.status)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)

        assertEquals(1, testLimitDatabaseAccessor.ordersDone.size)
        assertNotNull(testLimitDatabaseAccessor.ordersDone.first())

        assertEquals(-500.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "EUR" }?.volume)
        assertEquals(750.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "USD" }?.volume)
        assertEquals(500.0, testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "EUR" }?.volume)
        assertEquals(-750.0, testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "USD" }?.volume)

        assertEquals(0.0, testWalletDatabaseAcessor.getBalance("Client3", "EUR"), DELTA)
        assertEquals(750.0, testWalletDatabaseAcessor.getBalance("Client3", "USD"), DELTA)
        assertEquals(500.0, testWalletDatabaseAcessor.getBalance("Client4", "EUR"), DELTA)
        assertEquals(0.0, testWalletDatabaseAcessor.getBalance("Client4", "USD"), DELTA)

        val swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(750.0, swap.Amount1, DELTA)
        assertEquals("USD", swap.origAsset1)
        assertEquals("Client3", swap.clientId2)
        assertEquals(500.0, swap.Amount2, DELTA)
        assertEquals("EUR", swap.origAsset2)
    }

    @Test
    fun testNotStraightMatchOneToMany() {
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.4, volume = -100.0, clientId = "Client3"))
        testLimitDatabaseAccessor.addLimitOrder(buildLimitOrder(price = 1.5, volume = -1000.0, clientId = "Client1"))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client1", "EUR", 3000.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client3", "EUR", 3000.0))
        testWalletDatabaseAcessor.insertOrUpdateWallet(buildWallet("Client4", "USD", 2000.0))
        initServices()

        service.processMessage(buildMarketOrderWrapper(buildMarketOrder(clientId = "Client4", assetId = "EURUSD", volume = -1490.0, straight = false)))

        val marketOrder = testDatabaseAccessor.orders.first()
        assertEquals(Matched.name, marketOrder.status)
        assertEquals(1.49, marketOrder.price!!, DELTA)
        assertEquals(2, testDatabaseAccessor.matchingData.filter { it.masterOrderId == marketOrder.id }.size)
        assertEquals(16, testDatabaseAccessor.orderTradesLinks.size)

        assertEquals(1, testLimitDatabaseAccessor.orders.size)
        assertEquals(1, testLimitDatabaseAccessor.ordersDone.size)

        val activeLimitOrder = testLimitDatabaseAccessor.orders.first()
        assertEquals(-100.0, activeLimitOrder.remainingVolume, DELTA)
        assertEquals(Processing.name, activeLimitOrder.status)


        val limitOrder = testLimitDatabaseAccessor.ordersDone.first()
        assertEquals(Matched.name, limitOrder.status)
        assertEquals(1, testDatabaseAccessor.matchingData.filter { it.masterOrderId == limitOrder.id }.size)

        assertEquals(-100.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "EUR" }!!.volume, DELTA)
        assertEquals(140.0, testDatabaseAccessor.trades.find { it.clientId == "Client3" && it.assetId == "USD" }!!.volume, DELTA)
        assertEquals(-900.0, testDatabaseAccessor.trades.find { it.clientId == "Client1" && it.assetId == "EUR" }!!.volume, DELTA)
        assertEquals(1350.0, testDatabaseAccessor.trades.find { it.clientId == "Client1" && it.assetId == "USD" }!!.volume, DELTA)
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "EUR" && it.volume == 100.0})
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "USD" && it.volume == -140.0 })
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "EUR" && it.volume < 900.0})
        assertNotNull(testDatabaseAccessor.trades.find { it.clientId == "Client4" && it.assetId == "USD" && it.volume == -1350.0 })

        assertEquals(2900.0, testWalletDatabaseAcessor.getBalance("Client3", "EUR"), DELTA)
        assertEquals(140.0, testWalletDatabaseAcessor.getBalance("Client3", "USD"), DELTA)
        assertEquals(2100.0, testWalletDatabaseAcessor.getBalance("Client1", "EUR"), DELTA)
        assertEquals(1350.0, testWalletDatabaseAcessor.getBalance("Client1", "USD"), DELTA)
        assertEquals(1000.0, testWalletDatabaseAcessor.getBalance("Client4", "EUR"), DELTA)
        assertEquals(510.0, testWalletDatabaseAcessor.getBalance("Client4", "USD"), DELTA)

        var swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(140.0, swap.Amount1, DELTA)
        assertEquals("USD", swap.origAsset1)
        assertEquals("Client3", swap.clientId2)
        assertEquals(100.0, swap.Amount2, DELTA)
        assertEquals("EUR", swap.origAsset2)

        swap = transactionQueue.take() as Swap
        assertEquals("Client4", swap.clientId1)
        assertEquals(1350.0, swap.Amount1, DELTA)
        assertEquals("USD", swap.origAsset1)
        assertEquals("Client1", swap.clientId2)
        assertEquals(900.0, swap.Amount2, DELTA)
        assertEquals("EUR", swap.origAsset2)
    }
}
