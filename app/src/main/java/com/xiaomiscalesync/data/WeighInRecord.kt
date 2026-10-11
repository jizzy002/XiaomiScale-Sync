package com.xiaomiscalesync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a weigh-in was first seen. */
enum class WeighInSource { BROADCAST, LIVE, STORED }

/**
 * Owner attribution. `GUEST` is only ever assigned explicitly ("keep as
 * guest"); scale-flag ambiguity lands in `NEEDS_REVIEW`.
 */
enum class Category { MINE, NEEDS_REVIEW, GUEST }

/** Garmin push lifecycle of an archived row. */
enum class GarminStatus { PENDING, PUSHED, SKIPPED_EXISTING, AUTH_REQUIRED }

/**
 * One archived weigh-in. Weights are 1/100 kg (8025 = 80.25 kg) — the
 * plan-wide field name `weightGrams` is kept verbatim despite the unit
 * (global rename deferred to final review).
 *
 * The unique index is the dedupe key (recordedAt unix second, weight): the
 * archive can never hold the same weigh-in twice, even if the merger were
 * bypassed.
 */
@Entity(
    tableName = "weigh_ins",
    indices = [Index(value = ["recordedAt", "weightGrams"], unique = true)],
)
data class WeighInRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weightGrams: Int,
    val recordedAt: Long,
    val source: WeighInSource,
    val recordNo: Long?,
    val scaleFlag: Int,
    val memberMid: Long,
    val category: Category,
    val garminStatus: GarminStatus,
    val pushedAt: Long? = null,
    val createdAt: Long,
)

/** A weigh-in seen for the first time, before classification. */
data class NewWeighIn(
    val weightGrams: Int,
    val recordedAt: Long,
    val source: WeighInSource,
    val recordNo: Long?,
    val scaleFlag: Int,
    val memberMid: Long,
)

/**
 * One record number the user asked to clear from the *scale's* memory
 * (action 6.2). The app archive is never affected by scale cleanup.
 */
@Entity(
    tableName = "scale_cleanup",
    indices = [Index(value = ["recordNo"], unique = true)],
)
data class ScaleCleanupItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordNo: Long,
)
