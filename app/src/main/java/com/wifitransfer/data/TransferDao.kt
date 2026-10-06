package com.wifitransfer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<TransferRecord>>

    @Insert
    suspend fun insert(record: TransferRecord)

    @Delete
    suspend fun delete(record: TransferRecord)

    @Query("DELETE FROM transfer_history")
    suspend fun clearAll()
}
