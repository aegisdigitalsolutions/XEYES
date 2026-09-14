package com.rfmapper.data.room

import com.rfmapper.core.export.ExportEngine
import com.rfmapper.core.export.ExportEngineV1
import com.rfmapper.core.export.ExportPackage
import com.rfmapper.core.export.ExportRequest
import com.rfmapper.core.export.ExportResult
import com.rfmapper.core.export.ExportSink
import com.rfmapper.core.export.ObservationSource
import com.rfmapper.core.export.ObservationSummary
import com.rfmapper.core.model.DateRange
import com.rfmapper.core.model.ExportKind
import com.rfmapper.core.model.Generator
import com.rfmapper.core.model.Iso8601
import com.rfmapper.core.model.ObserverIdentity
import com.rfmapper.core.model.SessionSummary
import com.rfmapper.data.room.raw.SessionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Assembles an export package from the database.
 *
 * The two operations the specification names — `EXPORT TODAY` and `EXPORT SESSION` — differ only in
 * how the rows are selected, so they share everything else: the same engine, the same package
 * layout, the same checksums. That is deliberate; a session export that differed structurally from
 * a day export would double the surface the importer has to handle.
 */
class PackageExporter(
    private val repository: ObservationRepository,
    private val sessions: SessionDao,
    private val appVersion: String,
    private val engine: ExportEngine = ExportEngineV1(),
    private val newExportId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    data class Plan(
        val packageName: String,
        val request: ExportRequest,
        val source: ObservationSource,
    ) {
        val observationCount: Long get() = request.summary.observationCount
        val isEmpty: Boolean get() = observationCount == 0L
    }

    /** Everything one UTC day holds, whichever sessions it spans. */
    suspend fun planDay(observer: ObserverIdentity, dateStamp: String): Plan {
        val (from, to) = ObservationRepository.utcDayBounds(dateStamp)
        val summary = repository.summariseRange(from, to)
        return Plan(
            packageName = ExportPackage.fileName(observer.observerId, dateStamp),
            request = request(
                observer = observer,
                kind = ExportKind.DAY,
                summary = summary,
                dateRange = DateRange(from = Iso8601.format(from), to = Iso8601.format(to)),
                sessions = sessionSummaries(summary.sessionIds),
            ),
            source = repository.rangeExportSource(from, to),
        )
    }

    /** One collection session, which may cross midnight. */
    suspend fun planSession(observer: ObserverIdentity, sessionId: String): Plan {
        val summary = repository.summariseSession(sessionId)
        val dateStamp = summary.firstObservationUtc?.take(10)
            ?: Iso8601.utcDateStamp(nowMillis())
        val first = summary.firstObservationUtc
        val last = summary.lastObservationUtc
        val range = if (first != null && last != null) DateRange(from = first, to = last) else null
        return Plan(
            packageName = ExportPackage.fileName(observer.observerId, dateStamp, sessionId),
            request = request(
                observer = observer,
                kind = ExportKind.SESSION,
                summary = summary,
                dateRange = range,
                sessions = sessionSummaries(listOf(sessionId)),
                sessionIdForName = sessionId,
            ),
            source = repository.sessionExportSource(sessionId),
        )
    }

    /**
     * Writes [plan] into [sink].
     *
     * Callers write to a temporary document and rename on success: `checksum.txt` makes a truncated
     * package detectable, but a half-written file that never appears under its final name is better
     * than one an administrator has to be told to distrust.
     */
    suspend fun write(plan: Plan, sink: ExportSink): ExportResult = withContext(Dispatchers.IO) {
        engine.write(plan.request, plan.source, sink)
    }

    private suspend fun sessionSummaries(sessionIds: List<String>): List<SessionSummary> =
        if (sessionIds.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { sessions.byIds(sessionIds).map { it.toSummary() } }
        }

    private fun request(
        observer: ObserverIdentity,
        kind: ExportKind,
        summary: ObservationSummary,
        dateRange: DateRange?,
        sessions: List<SessionSummary>,
        sessionIdForName: String? = null,
    ) = ExportRequest(
        exportId = newExportId(),
        observer = observer,
        exportKind = kind,
        createdAtEpochMillis = nowMillis(),
        summary = summary,
        dateRange = dateRange,
        sessions = sessions,
        appVersion = appVersion,
        generator = Generator(GENERATOR_NAME, appVersion),
        sessionIdForName = sessionIdForName,
    )

    private companion object {
        const val GENERATOR_NAME = "RFMapper Collector"
    }
}
