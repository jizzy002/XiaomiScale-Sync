package com.xiaomiscalesync.proto

import com.xiaomiscalesync.data.Settings

/**
 * The user profile [SyncEngine] pushes to the scale with action 7.1 on
 * every connection — it sets the scale's clock and drives user
 * recognition ("wt" is the reference weight the flag/mid attribution is
 * judged against).
 *
 * Only `mid` and `weightGrams` affect the archive; the rest is display
 * metadata on the scale. Onboarding (Task 14) and Settings (Task 17)
 * construct this from user input.
 */
data class Profile(
    val mid: String = Settings.DEFAULT_MID,
    val age: Int = 30,
    val sex: Int = 1,
    val heightCm: Int = 180,

    /**
     * Latest known weight in 1/100 kg (8025 = 80.25 kg) — the fallback
     * when the archive is empty ("or 8000" per the profile-template rule).
     */
    val weightGrams: Int = 8000,
)

/**
 * The action 7.1 profile JSON — exact key order from the plan's Global
 * Constraints, verified against the reference client's captured app
 * traffic:
 *
 * ```
 * {"mid":"<member id>","duid":1,"uc":1,"ow":1,"unit":1,"time":<unix now>,"ud":[{"duid":1,"ut":1,"age":<age>,"sex":<sex>,"hi":<height cm>,"wt":<latest weight 1/100 kg>}]}
 * ```
 *
 * `unit` is always 1 (kg); `mid` defaults to `"1"`.
 */
object ProfileJson {

    fun build(
        mid: String,
        age: Int,
        sex: Int,
        heightCm: Int,
        weightGrams: Int,
        nowUnix: Long,
    ): String =
        """{"mid":"$mid","duid":1,"uc":1,"ow":1,"unit":1,"time":$nowUnix,"ud":[{"duid":1,"ut":1,"age":$age,"sex":$sex,"hi":$heightCm,"wt":$weightGrams}]}"""
}

/**
 * The profile the engine pushes, from persisted [Settings] plus the latest
 * archived weight. Unanswered fields (a pre-Task-14 install) keep the
 * [Profile] defaults; [weightGrams] null → the 8000 fallback.
 */
fun Settings.toProfile(weightGrams: Int?): Profile {
    val defaults = Profile()
    return Profile(
        mid = mid,
        age = age ?: defaults.age,
        sex = sex,
        heightCm = heightCm ?: defaults.heightCm,
        weightGrams = weightGrams ?: defaults.weightGrams,
    )
}
