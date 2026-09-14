package com.rfmapper.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rfmapper.data.room.derived.DerivedDao
import com.rfmapper.data.room.derived.DerivedGenerationEntity
import com.rfmapper.data.room.derived.MovementEstimateEntity
import com.rfmapper.data.room.derived.PositionEstimateEntity
import com.rfmapper.data.room.derived.QualityFlagEntity
import com.rfmapper.data.room.derived.ZoneTransitionEntity
import com.rfmapper.data.room.raw.ImportBatchDao
import com.rfmapper.data.room.raw.ImportBatchEntity
import com.rfmapper.data.room.raw.ObservationDao
import com.rfmapper.data.room.raw.RawObservationEntity
import com.rfmapper.data.room.raw.RetentionDao
import com.rfmapper.data.room.raw.SessionDao
import com.rfmapper.data.room.raw.SessionEntity
import com.rfmapper.data.room.reference.BuildingEntity
import com.rfmapper.data.room.reference.DeviceIdentifierEntity
import com.rfmapper.data.room.reference.FingerprintDao
import com.rfmapper.data.room.reference.FingerprintEntity
import com.rfmapper.data.room.reference.FingerprintEntryEntity
import com.rfmapper.data.room.reference.InfrastructureDao
import com.rfmapper.data.room.reference.InfrastructureNodeEntity
import com.rfmapper.data.room.reference.ManagedDeviceDao
import com.rfmapper.data.room.reference.ManagedDeviceEntity
import com.rfmapper.data.room.reference.ObserverCalibrationDao
import com.rfmapper.data.room.reference.ObserverCalibrationEntity
import com.rfmapper.data.room.reference.ObserverDao
import com.rfmapper.data.room.reference.ObserverEntity
import com.rfmapper.data.room.reference.SiteModelDao
import com.rfmapper.data.room.reference.SurveyPointEntity
import com.rfmapper.data.room.reference.ZoneEdgeEntity
import com.rfmapper.data.room.reference.ZoneEntity

/**
 * One database, three layers, built from one module so the Collector and the Master cannot drift
 * apart on storage semantics. Each app uses the table groups it needs: the Collector writes RAW and
 * reads a little REFERENCE, the Master uses all three.
 *
 * @see <a href="../../../../../../../../docs/04-room-entity-dao-design.md">docs/04</a>
 */
@Database(
    version = RfMapperDatabase.VERSION,
    exportSchema = true,
    entities = [
        // RAW
        RawObservationEntity::class,
        SessionEntity::class,
        ImportBatchEntity::class,
        // REFERENCE
        ManagedDeviceEntity::class,
        DeviceIdentifierEntity::class,
        InfrastructureNodeEntity::class,
        ObserverEntity::class,
        BuildingEntity::class,
        ZoneEntity::class,
        ZoneEdgeEntity::class,
        SurveyPointEntity::class,
        FingerprintEntity::class,
        FingerprintEntryEntity::class,
        ObserverCalibrationEntity::class,
        // DERIVED
        PositionEstimateEntity::class,
        ZoneTransitionEntity::class,
        MovementEstimateEntity::class,
        QualityFlagEntity::class,
        DerivedGenerationEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class RfMapperDatabase : RoomDatabase() {

    abstract fun observationDao(): ObservationDao
    abstract fun sessionDao(): SessionDao
    abstract fun retentionDao(): RetentionDao
    abstract fun importBatchDao(): ImportBatchDao

    abstract fun managedDeviceDao(): ManagedDeviceDao
    abstract fun infrastructureDao(): InfrastructureDao
    abstract fun observerDao(): ObserverDao
    abstract fun siteModelDao(): SiteModelDao
    abstract fun fingerprintDao(): FingerprintDao
    abstract fun observerCalibrationDao(): ObserverCalibrationDao

    abstract fun derivedDao(): DerivedDao

    companion object {
        const val VERSION = 1
        const val NAME = "rfmapper.db"

        /**
         * Opens the database for real use.
         *
         * `fallbackToDestructiveMigration` is deliberately absent. Raw observations are field work:
         * a day of walking a site with two phones cannot be recollected, and a migration the
         * developer forgot must fail loudly at development time rather than silently drop a table on
         * a field engineer's device. Every schema change ships an explicit migration and a test that
         * opens a fixture of the previous version and asserts the data survived.
         */
        fun open(context: Context, name: String = NAME): RfMapperDatabase =
            Room.databaseBuilder(context, RfMapperDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)
                .addCallback(WalAndForeignKeys)
                .build()

        /** In-memory instance for tests. Never used by an app. */
        fun openInMemory(context: Context): RfMapperDatabase =
            Room.inMemoryDatabaseBuilder(context, RfMapperDatabase::class.java)
                .addCallback(WalAndForeignKeys)
                .allowMainThreadQueries()
                .build()

        /** Empty at version 1, and the array exists so adding the first migration is a one-line change. */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()
    }

    /**
     * Foreign keys are off by default in SQLite and have to be enabled per connection. They are the
     * mechanism that stops an identifier from outliving the device it attributes to, so leaving them
     * off would quietly turn the schema's constraints into documentation.
     */
    private object WalAndForeignKeys : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA foreign_keys = ON")
        }
    }
}
