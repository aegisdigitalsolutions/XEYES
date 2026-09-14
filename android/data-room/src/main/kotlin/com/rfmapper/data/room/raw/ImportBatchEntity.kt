package com.rfmapper.data.room.raw

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One import attempt, previewed or committed.
 *
 * [packageSha256] carries a unique index, so re-importing a byte-identical file is caught before a
 * single row is parsed. Deduplication by `observation_id` still does the real work for the common
 * case of overlapping-but-not-identical packages — `EXPORT TODAY` run twice on the same day, with
 * more data the second time.
 *
 * A previewed-but-not-committed batch is kept rather than discarded, because "I looked at this
 * package and decided not to import it" is itself something an administrator will want to see when
 * the same file turns up again a week later.
 */
@Entity(
    tableName = "raw_import_batch",
    indices = [
        Index(value = ["package_sha256"], unique = true),
        Index("observer_id"),
        Index("imported_at_utc"),
    ],
)
data class ImportBatchEntity(
    @PrimaryKey @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    @ColumnInfo(name = "observer_id") val observerId: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "package_sha256") val packageSha256: String,
    @ColumnInfo(name = "imported_at_utc") val importedAtUtc: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: String,
    @ColumnInfo(name = "declared_count") val declaredCount: Long,
    @ColumnInfo(name = "accepted_count") val acceptedCount: Long,
    @ColumnInfo(name = "duplicate_count") val duplicateCount: Long,
    @ColumnInfo(name = "invalid_count") val invalidCount: Long,
    @ColumnInfo(name = "manifest_json") val manifestJson: String,
    @ColumnInfo(name = "issues_json") val issuesJson: String?,
    @ColumnInfo(name = "operator") val operator: String?,
    @ColumnInfo(name = "status") val status: String,
) {
    enum class Status { PREVIEWED, COMMITTED, REJECTED }
}

@Dao
interface ImportBatchDao {

    @Upsert
    suspend fun upsert(batch: ImportBatchEntity)

    @Query("SELECT * FROM raw_import_batch WHERE import_batch_id = :importBatchId")
    suspend fun byId(importBatchId: String): ImportBatchEntity?

    /** The cheap pre-check: has this exact file been seen before? */
    @Query("SELECT * FROM raw_import_batch WHERE package_sha256 = :sha256 LIMIT 1")
    suspend fun byPackageSha256(sha256: String): ImportBatchEntity?

    @Query("SELECT * FROM raw_import_batch ORDER BY imported_at_utc DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<ImportBatchEntity>>

    @Query("SELECT * FROM raw_import_batch WHERE status = 'COMMITTED' ORDER BY imported_at_utc DESC")
    suspend fun committed(): List<ImportBatchEntity>

    @Query("UPDATE raw_import_batch SET status = :status WHERE import_batch_id = :importBatchId")
    suspend fun setStatus(importBatchId: String, status: String)

    @Query("SELECT COUNT(*) FROM raw_import_batch WHERE status = 'COMMITTED'")
    fun observeCommittedCount(): Flow<Int>
}
