package com.lykke.matching.engine.database.azure

import com.microsoft.azure.storage.CloudStorageAccount
import com.microsoft.azure.storage.table.CloudTable
import com.microsoft.azure.storage.table.TableBatchOperation
import com.microsoft.azure.storage.table.TableEntity

fun getOrCreateTable(connectionString: String, tableName: String): CloudTable {
    val storageAccount = CloudStorageAccount.parse(connectionString)
    val tableClient = storageAccount.createCloudTableClient()

    val cloudTable = tableClient.getTableReference(tableName)
    cloudTable.createIfNotExists()
    return cloudTable
}

val AZURE_BATCH_SIZE = 100

fun batchInsertOrMerge(table: CloudTable, elements: List<TableEntity>) {
    var batchOperation = TableBatchOperation()
    if (elements.size <= AZURE_BATCH_SIZE) {
        elements.forEach { element ->
            batchOperation.insertOrMerge(element)
        }
        table.execute(batchOperation)
    } else {
        elements.forEachIndexed { index, element ->
            batchOperation.insertOrMerge(element)
            if ((index + 1) % AZURE_BATCH_SIZE == 0) {
                table.execute(batchOperation)
                batchOperation = TableBatchOperation()
            }
        }
        if (batchOperation.size > 0) {
            table.execute(batchOperation)
        }
    }
}
fun batchDelete(table: CloudTable, elements: List<TableEntity>) {
    var batchOperation = TableBatchOperation()
    if (elements.size <= AZURE_BATCH_SIZE) {
        elements.forEach { element ->
            batchOperation.delete(element)
        }
        table.execute(batchOperation)
    } else {
        elements.forEachIndexed { index, element ->
            batchOperation.delete(element)
            if ((index + 1) % AZURE_BATCH_SIZE == 0) {
                table.execute(batchOperation)
                batchOperation = TableBatchOperation()
            }
        }
        if (batchOperation.size > 0) {
            table.execute(batchOperation)
        }
    }
}