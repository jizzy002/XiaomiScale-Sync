package com.xiaomiscalesync.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Onboarding/profile settings. `backfillCutoff = null` pushes everything;
 * `Some(unixSeconds)` archives anything older as SKIPPED_EXISTING.
 */
data class Settings(
    val onboarded: Boolean,
    val backfillCutoff: Long?,
    val mid: String,
    /** Onboarding/Settings profile answers; null until the wizard finishes. */
    val heightCm: Int? = null,
    val age: Int? = null,
    /** Scale convention: 1 = male, 2 = female. */
    val sex: Int = 1,
) {
    companion object {
        const val DEFAULT_MID = "1"
        val DEFAULT = Settings(onboarded = false, backfillCutoff = null, mid = DEFAULT_MID)
    }
}

/** Read seam so the repository stays JVM-testable without DataStore. */
fun interface SettingsSource {
    suspend fun current(): Settings
}

/**
 * Write seam for the onboarding decisions (plan Task 14): `mid`,
 * `backfillCutoff` and the profile are written once at wizard completion, `onboarded` flips
 * true exactly then and only then. Lets `OnboardingViewModel`-level tests
 * run on the JVM without DataStore.
 */
interface SettingsWriter {
    suspend fun setOnboarded(v: Boolean)

    /** Unix seconds; null clears the cutoff (fresh start, everything pushable). */
    suspend fun setBackfillCutoff(v: Long?)

    suspend fun setMid(v: String)

    /** The scale profile answers (action 7.1 metadata), written at Finish. */
    suspend fun setProfile(heightCm: Int, age: Int, sex: Int)
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** DataStore-backed [SettingsSource]/[SettingsWriter]; the app wires the application context once. */
class SettingsStore(private val dataStore: DataStore<Preferences>) : SettingsSource, SettingsWriter {

    val settings: Flow<Settings> = dataStore.data.map { it.toSettings() }

    override suspend fun current(): Settings = settings.first()

    override suspend fun setOnboarded(v: Boolean) {
        dataStore.edit { it[KEY_ONBOARDED] = v }
    }

    /** Unix seconds; null clears the cutoff (fresh start, everything pushable). */
    override suspend fun setBackfillCutoff(v: Long?) {
        dataStore.edit { p ->
            if (v == null) p.remove(KEY_CUTOFF) else p[KEY_CUTOFF] = v
        }
    }

    override suspend fun setMid(v: String) {
        dataStore.edit { it[KEY_MID] = v }
    }

    override suspend fun setProfile(heightCm: Int, age: Int, sex: Int) {
        dataStore.edit {
            it[KEY_HEIGHT] = heightCm
            it[KEY_AGE] = age
            it[KEY_SEX] = sex
        }
    }

    private fun Preferences.toSettings(): Settings = Settings(
        onboarded = this[KEY_ONBOARDED] ?: false,
        backfillCutoff = this[KEY_CUTOFF],
        mid = this[KEY_MID] ?: Settings.DEFAULT_MID,
        heightCm = this[KEY_HEIGHT],
        age = this[KEY_AGE],
        sex = this[KEY_SEX] ?: 1,
    )

    companion object {
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        private val KEY_CUTOFF = longPreferencesKey("backfill_cutoff")
        private val KEY_MID = stringPreferencesKey("mid")
        private val KEY_HEIGHT = intPreferencesKey("height_cm")
        private val KEY_AGE = intPreferencesKey("age")
        private val KEY_SEX = intPreferencesKey("sex")

        fun of(context: Context): SettingsStore = SettingsStore(context.settingsDataStore)
    }
}
