package com.rfmapper.collector

import android.app.Application
import android.content.Context
import com.rfmapper.collector.collection.CollectionCoordinator
import com.rfmapper.collector.export.ExportCoordinator
import com.rfmapper.collector.settings.ObserverSettings
import com.rfmapper.data.room.ObservationRepository
import com.rfmapper.data.room.PackageExporter
import com.rfmapper.data.room.RfMapperDatabase

class CollectorApplication : Application() {

    val graph: CollectorGraph by lazy { CollectorGraph(this) }
}

/**
 * The application's object graph, assembled by hand.
 *
 * Hand-wiring rather than a DI framework: the Collector has exactly one long-lived scope and about
 * six singletons, so an annotation processor would add build time and a layer of indirection
 * without removing any real complexity. The pieces that genuinely need substituting in tests —
 * clocks, id generators, radio providers, export sinks — are already constructor parameters on the
 * `core-*` types, which is where the seams belong.
 *
 * There is deliberately one [CollectionCoordinator] per process. Two would each run a Wi-Fi scan
 * loop and compete for the same platform scan quota.
 */
class CollectorGraph(private val context: Context) {

    val appVersion: String = BuildConfig.COLLECTOR_VERSION

    val database: RfMapperDatabase by lazy { RfMapperDatabase.open(context) }

    val settings: ObserverSettings by lazy { ObserverSettings(context) }

    val repository: ObservationRepository by lazy {
        ObservationRepository(database.observationDao(), database.sessionDao())
    }

    val coordinator: CollectionCoordinator by lazy {
        CollectionCoordinator(
            context = context,
            repository = repository,
            settings = settings,
            appVersion = appVersion,
        )
    }

    val exporter: PackageExporter by lazy {
        PackageExporter(
            repository = repository,
            sessions = database.sessionDao(),
            appVersion = appVersion,
        )
    }

    val export: ExportCoordinator by lazy {
        ExportCoordinator(
            context = context,
            exporter = exporter,
            settings = settings,
            collection = coordinator,
            appVersion = appVersion,
        )
    }

    companion object {
        fun from(context: Context): CollectorGraph =
            (context.applicationContext as CollectorApplication).graph
    }
}
