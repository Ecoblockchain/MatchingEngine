package com.lykke.matching.engine.order

enum class OrderStatus {
    //Init status, limit order in order book
    InOrderBook
    //Partially matched
    ,Processing
    //Fully matched
    ,Matched
    //Not enough funds on account
    ,NotEnoughFunds
    //No liquidity
    ,NoLiquidity
    //Unknown asset
    ,UnknownAsset
    //One of trades or whole order has volume/price*volume less then configured dust
    ,Dust
    //Cancelled
    ,Cancelled
}