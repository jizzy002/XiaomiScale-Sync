package com.xiaomiscalesync.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaomiscalesync.sync.SyncKind
import com.xiaomiscalesync.sync.SyncResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Durable summary of the most recent completed scale check. */
data class SyncStatus(
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val outcome: SyncOutcome? = null,
    /** Newly archived readings from the scale's stored-record fetch. */
    val readingsArchived: Int = 0,
    val lastSuccessArchived: Int = 0,
)

enum class SyncOutcome { SUCCESS, SCALE_NOT_FOUND, FAILED }

private val Context.syncStatusDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_status")

/** Persists sync outcomes without coupling the engine or UI to Android preferences. */
class SyncStatusStore(private val dataStore: DataStore<Preferences>) {
    val status: Flow<SyncStatus> = dataStore.data.map { prefs ->
        SyncStatus(
            lastAttemptAt = prefs[KEY_ATTEMPT],
            lastSuccessAt = prefs[KEY_SUCCESS],
            outcome = prefs[KEY_OUTCOME]?.let { value -> SyncOutcome.entries.firstOrNull { it.name == value } },
            readingsArchived = prefs[KEY_ARCHIVED] ?: 0,
            lastSuccessArchived = prefs[KEY_SUCCESS_ARCHIVED] ?: 0,
        )
    }

    suspend fun record(result: SyncResult, finishedAt: Long) {
        val outcome = when (result.kind) {
            SyncKind.OK -> SyncOutcome.SUCCESS
            SyncKind.SCALE_ABSENT -> SyncOutcome.SCALE_NOT_FOUND
            SyncKind.FAILED -> SyncOutcome.FAILED
        }
        dataStore.edit { prefs ->
            prefs[KEY_ATTEMPT] = finishedAt
            prefs[KEY_OUTCOME] = outcome.name
            prefs[KEY_ARCHIVED] = result.collected
            if (result.success) {
                prefs[KEY_SUCCESS] = finishedAt
                prefs[KEY_SUCCESS_ARCHIVED] = result.collected
            }
        }
    }

    companion object {
        private val KEY_ATTEMPT = longPreferencesKey("last_attempt_at")
        private val KEY_SUCCESS = longPreferencesKey("last_success_at")
        private val KEY_OUTCOME = stringPreferencesKey("last_outcome")
        private val KEY_ARCHIVED = androidx.datastore.preferences.core.intPreferencesKey("readings_archived")
        private val KEY_SUCCESS_ARCHIVED = androidx.datastore.preferences.core.intPreferencesKey("last_success_archived")

        fun of(context: Context) = SyncStatusStore(context.syncStatusDataStore)
    }
}
