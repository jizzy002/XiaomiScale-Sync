package com.xiaomiscalesync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncActivityDao {
    @Query("SELECT * FROM sync_activity ORDER BY occurredAt DESC, id DESC LIMIT 100")
    fun observeRecent(): Flow<List<SyncActivityEvent>>

    @Insert
    suspend fun insert(event: SyncActivityEvent)

    @Query(
        "DELETE FROM sync_activity WHERE id NOT IN " +
            "(SELECT id FROM sync_activity ORDER BY occurredAt DESC, id DESC LIMIT :limit)",
    )
    suspend fun keepRecent(limit: Int)

    @Transaction
    suspend fun insertAndTrim(event: SyncActivityEvent, limit: Int) {
        insert(event)
        keepRecent(limit)
    }
}
