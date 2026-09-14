package com.rfmapper.master

import android.app.Application
import android.content.Context
import com.rfmapper.core.importing.ImportEngineV1
import com.rfmapper.core.importing.ObserverRegistry
import com.rfmapper.data.room.DerivedPackageImporter
import com.rfmapper.data.room.DerivedRepository
import com.rfmapper.data.room.ObservationRepository
import com.rfmapper.data.room.PackageImporter
import com.rfmapper.data.room.ReferenceRepository
import com.rfmapper.data.room.RfMapperDatabase
import com.rfmapper.data.room.SiteModelIo
import com.rfmapper.master.importing.ImportCoordinator
import com.rfmapper.master.settings.MasterSettings

class MasterApplication : Application() {

    val graph: MasterGraph by lazy { MasterGraph(this) }
}

/**
 * The Master's object graph, assembled by hand for the same reason the Collector's is: a handful of
 * process-lifetime singletons and no substitution needs that constructor parameters do not already
 * cover.
 */
class MasterGraph(private val context: Context) {

    val appVersion: String = BuildConfig.MASTER_VERSION

    val database: RfMapperDatabase by lazy { RfMapperDatabase.open(context) }

    val settings: MasterSettings by lazy { MasterSettings(context) }

    val observations: ObservationRepository by lazy {
        ObservationRepository(database.observationDao(), database.sessionDao())
    }

    val reference: ReferenceRepository by lazy {
        ReferenceRepository(
            devices = database.managedDeviceDao(),
            infrastructure = database.infrastructureDao(),
            observers = database.observerDao(),
            site = database.siteModelDao(),
            fingerprints = database.fingerprintDao(),
            calibration = database.observerCalibrationDao(),
        )
    }

    val derived: DerivedRepository by lazy { DerivedRepository(database.derivedDao()) }

    val siteModelIo: SiteModelIo by lazy {
        SiteModelIo(
            site = database.siteModelDao(),
            infrastructure = database.infrastructureDao(),
            observers = database.observerDao(),
            fingerprints = database.fingerprintDao(),
            calibration = database.observerCalibrationDao(),
        )
    }

    /**
     * The engine is constructed with a permissive registry only as a default; [PackageImporter]
     * re-reads the enrolled set from the database for each preview, so enrolling an observer and
     * immediately retrying a rejected import works.
     */
    val packageImporter: PackageImporter by lazy {
        PackageImporter(
            repository = observations,
            observers = database.observerDao(),
            devices = database.managedDeviceDao(),
            batches = database.importBatchDao(),
            engine = ImportEngineV1(ObserverRegistry { true }),
        )
    }

    val derivedImporter: DerivedPackageImporter by lazy {
        DerivedPackageImporter(derived = database.derivedDao(), batches = database.importBatchDao())
    }

    val importer: ImportCoordinator by lazy {
        ImportCoordinator(
            context = context,
            observations = packageImporter,
            derived = derivedImporter,
            siteModel = siteModelIo,
            settings = settings,
        )
    }

    companion object {
        fun from(context: Context): MasterGraph =
            (context.applicationContext as MasterApplication).graph
    }
}
