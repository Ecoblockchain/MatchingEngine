package com.lykke.matching.engine.database.azure

import com.lykke.matching.engine.database.HistoryTicksDatabaseAccessor
import com.lykke.matching.engine.history.TickBlobHolder
import com.lykke.matching.engine.logging.MetricsLogger
import com.microsoft.azure.storage.blob.CloudBlob
import com.microsoft.azure.storage.blob.CloudBlobContainer
import org.apache.log4j.Logger
import java.io.ByteArrayInputStream
import java.util.LinkedList

class AzureHistoryTicksDatabaseAccessor(historyTicksString: String) : HistoryTicksDatabaseAccessor {

    companion object {
        val LOGGER = Logger.getLogger(AzureHistoryTicksDatabaseAccessor::class.java.name)
        val METRICS_LOGGER = MetricsLogger.getLogger()
    }

    val historyBlobContainer: CloudBlobContainer

    override fun loadHistoryTick(asset: String, period: String): CloudBlob? {
        try {
            val blobItem = historyBlobContainer.getBlockBlobReference("BA_${asset}_$period")
            if (blobItem.exists()) {
                return blobItem
            }
        } catch (e: Exception) {
            LOGGER.error("Unable to load blobs", e)
            METRICS_LOGGER.logError(this.javaClass.name, "Unable to load blobs", e)
        }
        return null
    }

    override fun loadHistoryTicks(): List<CloudBlob> {
        val result = LinkedList<CloudBlob>()
        try {
            // Loop over blobs within the container and output the URI to each of them.
            historyBlobContainer.listBlobs()
                    .filterIsInstance<CloudBlob>()
                    .filterTo(result) { it.name.startsWith("BA_") }
        } catch (e: Exception) {
            LOGGER.error("Unable to load blobs", e)
            METRICS_LOGGER.logError(this.javaClass.name, "Unable to load blobs", e)
        }
        return result
    }

    override fun saveHistoryTick(tick: TickBlobHolder) {
        try {
            val blob = historyBlobContainer.getBlockBlobReference(tick.name)
            val byteArray = tick.convertToString().toByteArray()
            blob.upload(ByteArrayInputStream(byteArray), byteArray.size.toLong())
        } catch (e: Exception) {
            LOGGER.error("Unable to save blob ${tick.name}", e)
            METRICS_LOGGER.logError(this.javaClass.name, "Unable to save blob ${tick.name}", e)
        }
    }

    init {
        this.historyBlobContainer = getOrCreateBlob(historyTicksString, "history")
    }
}