package com.lykke.matching.engine.database

import com.lykke.matching.engine.daos.BestPrice
import com.lykke.matching.engine.daos.Candle
import com.lykke.matching.engine.daos.HourCandle
import com.lykke.matching.engine.daos.LimitOrder

interface LimitOrderDatabaseAccessor {
    fun loadLimitOrders(): List<LimitOrder>
    fun addLimitOrder(order: LimitOrder)
    fun addLimitOrders(orders: List<LimitOrder>)
    fun updateLimitOrder(order: LimitOrder)
    fun deleteLimitOrders(orders: List<LimitOrder>)

    fun addLimitOrderDone(order: LimitOrder)
    fun addLimitOrdersDone(orders: List<LimitOrder>)
    fun addLimitOrderDoneWithGeneratedRowId(order: LimitOrder)

    fun updateBestPrices(prices: List<BestPrice>)
    fun writeCandle(candle: Candle)

    fun getHoursCandles(): MutableList<HourCandle>
    fun writeHourCandles(candles: List<HourCandle>)
}