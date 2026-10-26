package com.xiaomiscalesync.data

import com.google.gson.Gson
import com.google.gson.JsonParseException

/** Versioned, credential-free archive format for moving weigh-ins between devices. */
data class ArchiveBackupDocument(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val weighIns: List<BackupWeighIn>?,
) {
    companion object {
        const val FORMAT = "xiaomi-scale-sync-archive"
        const val VERSION = 2
    }
}

data class BackupWeighIn(
    val weightCentiKg: Int,
    val recordedAt: Long,
    val source: String,
    val recordNo: Long?,
    val scaleFlag: Int,
    val memberMid: Long,
    val category: String,
    val garminStatus: String,
    val pushedAt: Long?,
    val createdAt: Long,
)

/** Pure encode/decode boundary; credentials and settings are deliberately absent. */
object ArchiveBackup {
    private val gson = Gson()

    private data class BackupHeader(val format: String?, val version: Int?)

    private data class LegacyArchiveBackupDocument(
        val format: String?,
        val version: Int?,
        val weighIns: List<LegacyBackupWeighIn>?,
    )

    /** Version 1 called the centi-kilogram value `weightGrams`. */
    private data class LegacyBackupWeighIn(
        val weightGrams: Int,
        val recordedAt: Long,
        val source: String,
        val recordNo: Long?,
        val scaleFlag: Int,
        val memberMid: Long,
        val category: String,
        val garminStatus: String,
        val pushedAt: Long?,
        val createdAt: Long,
    )

    fun encode(records: List<WeighInRecord>): String = gson.toJson(
        ArchiveBackupDocument(weighIns = records.map { it.toBackup() }),
    )

    fun decode(json: String): List<WeighInRecord> {
        require(json.length <= MAX_JSON_CHARS) { "Backup file is too large" }
        val header = parse<BackupHeader>(json) ?: throw IllegalArgumentException("Backup file is empty")
        require(header.format == ArchiveBackupDocument.FORMAT) { "This is not a Xiaomi Scale Sync backup" }
        val weighIns = when (header.version) {
            1 -> {
                val legacy = parse<LegacyArchiveBackupDocument>(json)
                    ?: throw IllegalArgumentException("Backup file is empty")
                legacy.weighIns?.map { row ->
                    BackupWeighIn(
                        weightCentiKg = row.weightGrams,
                        recordedAt = row.recordedAt,
                        source = row.source,
                        recordNo = row.recordNo,
                        scaleFlag = row.scaleFlag,
                        memberMid = row.memberMid,
                        category = row.category,
                        garminStatus = row.garminStatus,
                        pushedAt = row.pushedAt,
                        createdAt = row.createdAt,
                    )
                } ?: throw IllegalArgumentException("Backup is missing its weigh-in list")
            }
            ArchiveBackupDocument.VERSION -> {
                val document = parse<ArchiveBackupDocument>(json)
                    ?: throw IllegalArgumentException("Backup file is empty")
                document.weighIns ?: throw IllegalArgumentException("Backup is missing its weigh-in list")
            }
            else -> throw IllegalArgumentException("Unsupported backup version: ${header.version}")
        }
        require(weighIns.size <= MAX_RECORDS) { "Backup contains too many weigh-ins" }

        val keys = HashSet<Pair<Long, Int>>()
        return weighIns.map { row ->
            require(row.weightCentiKg in 100..30_000) { "Backup contains an invalid weight" }
            require(row.recordedAt in 1..MAX_UNIX_SECONDS && row.createdAt in 1..MAX_UNIX_SECONDS) {
                "Backup contains an invalid timestamp"
            }
            require(row.pushedAt == null || row.pushedAt in 1..MAX_UNIX_SECONDS) {
                "Backup contains an invalid Garmin timestamp"
            }
            require(row.recordNo == null || row.recordNo > 0) { "Backup contains an invalid scale record number" }
            require(keys.add(row.recordedAt to row.weightCentiKg)) { "Backup contains duplicate weigh-ins" }
            val source = enumValue<WeighInSource>(row.source, "source")
            val category = enumValue<Category>(row.category, "category")
            val status = enumValue<GarminStatus>(row.garminStatus, "Garmin status")
            WeighInRecord(
                id = 0,
                weightCentiKg = row.weightCentiKg,
                recordedAt = row.recordedAt,
                source = source,
                recordNo = row.recordNo,
                scaleFlag = row.scaleFlag,
                memberMid = row.memberMid,
                category = category,
                garminStatus = status,
                pushedAt = row.pushedAt,
                createdAt = row.createdAt,
            )
        }
    }

    private fun WeighInRecord.toBackup() = BackupWeighIn(
        weightCentiKg, recordedAt, source.name, recordNo, scaleFlag, memberMid,
        category.name, garminStatus.name, pushedAt, createdAt,
    )

    private inline fun <reified T> parse(json: String): T? = try {
        gson.fromJson(json, T::class.java)
    } catch (e: JsonParseException) {
        throw IllegalArgumentException("Backup file is not valid JSON", e)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Backup contains an unknown $label")

    private const val MAX_JSON_CHARS = 20_000_000
    private const val MAX_RECORDS = 250_000
    private const val MAX_UNIX_SECONDS = 253_402_300_799L
}
