package com.xiaomiscalesync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveBackupTest {
    private fun row(
        category: Category = Category.MINE,
        status: GarminStatus = GarminStatus.PUSHED,
    ) = WeighInRecord(
        id = 12,
        weightCentiKg = 8025,
        recordedAt = 1_790_000_000,
        source = WeighInSource.STORED,
        recordNo = 41,
        scaleFlag = 0,
        memberMid = 1,
        category = category,
        garminStatus = status,
        pushedAt = 1_790_000_100,
        createdAt = 1_790_000_001,
    )

    @Test
    fun roundTripPreservesArchiveFieldsButRegeneratesLocalId() {
        val restored = ArchiveBackup.decode(ArchiveBackup.encode(listOf(row()))).single()
        assertEquals(0L, restored.id)
        assertEquals(8025, restored.weightCentiKg)
        assertEquals(WeighInSource.STORED, restored.source)
        assertEquals(41L, restored.recordNo)
        assertEquals(Category.MINE, restored.category)
        assertEquals(GarminStatus.PUSHED, restored.garminStatus)
        assertEquals(1_790_000_100L, restored.pushedAt)
    }

    @Test
    fun preservesUnresolvedPendingAndGuestStatesWithoutCredentials() {
        val rows = listOf(
            row(Category.NEEDS_REVIEW, GarminStatus.PENDING),
            row().copy(recordedAt = 1_790_000_010, category = Category.GUEST, garminStatus = GarminStatus.PENDING),
        )
        val json = ArchiveBackup.encode(rows)
        assertEquals(rows.map { it.copy(id = 0) }, ArchiveBackup.decode(json))
        org.junit.Assert.assertFalse(json.contains("token"))
        org.junit.Assert.assertFalse(json.contains("bleKey"))
    }

    @Test
    fun rejectsUnknownFormatVersionAndDuplicateKeys() {
        assertThrows(IllegalArgumentException::class.java) { ArchiveBackup.decode("{}") }
        val valid = ArchiveBackup.encode(listOf(row()))
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveBackup.decode(valid.replace("\"version\":2", "\"version\":99"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveBackup.decode(ArchiveBackup.encode(listOf(row(), row().copy(id = 13))))
        }
    }

    @Test
    fun importsVersionOneBackupWithoutChangingStoredWeightValue() {
        val legacyJson = """{"format":"${ArchiveBackupDocument.FORMAT}","version":1,"weighIns":[{"weightGrams":8025,"recordedAt":1790000000,"source":"STORED","recordNo":41,"scaleFlag":0,"memberMid":1,"category":"MINE","garminStatus":"PUSHED","pushedAt":1790000100,"createdAt":1790000001}]}"""

        val restored = ArchiveBackup.decode(legacyJson).single()

        assertEquals(8025, restored.weightCentiKg)
        assertEquals(WeighInSource.STORED, restored.source)
    }

    @Test
    fun repositoryRestoreMergesAndLeavesExistingRecordUntouched() {
        val dao = FakeDao()
        val repo = WeighInRepository(
            dao,
            SettingsSource { Settings(onboarded = true, backfillCutoff = null, mid = "1") },
        )
        kotlinx.coroutines.runBlocking {
            dao.insert(row().copy(id = 0, category = Category.GUEST, garminStatus = GarminStatus.PENDING))
            val backup = ArchiveBackup.encode(
                listOf(row(), row().copy(recordedAt = 1_790_000_010, weightCentiKg = 8100)),
            )
            assertEquals(1, repo.restoreBackup(backup))
            val rows = dao.all()
            assertEquals(2, rows.size)
            assertEquals(Category.GUEST, rows.first { it.recordedAt == 1_790_000_000L }.category)
            assertEquals(GarminStatus.PENDING, rows.first { it.recordedAt == 1_790_000_000L }.garminStatus)
            assertEquals(41L, rows.first { it.recordedAt == 1_790_000_010L }.recordNo)
        }
    }
}
