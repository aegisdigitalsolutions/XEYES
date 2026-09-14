package com.rfmapper.master.importing

import android.content.Context
import android.net.Uri
import com.rfmapper.core.model.Iso8601
import com.rfmapper.data.room.DeviceRegistryIo
import com.rfmapper.data.room.SiteModelIo
import com.rfmapper.master.settings.MasterSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * Writes the REFERENCE layer out as the two files the Positioning Lab takes as input.
 *
 * The Lab is the only component that computes anything, and it computes from three things: the
 * observation packages the Collectors export, `site_model.json`, and the device registry. The first
 * comes from the Collector; the other two exist only in the Master's database, so without this
 * class the nightly pipeline has no geometry to place devices in and no registry to attribute them
 * against. Both files travel the way everything else does — written to storage by hand and carried
 * across, with no network path between the components.
 */
class ReferenceExporter(
    private val context: Context,
    private val siteModel: SiteModelIo,
    private val devices: DeviceRegistryIo,
    private val settings: MasterSettings,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    data class Outcome(val headline: String, val detail: List<String>, val success: Boolean)

    /** Dated, so successive exports do not silently overwrite each other. */
    fun suggestedRegistryName(): String = "devices_${dateStamp()}.json"

    /**
     * Exports the current site model.
     *
     * The frame is taken from the last model applied rather than defaulted. A site model exported
     * with an invented origin would still be a valid document, and every metre coordinate in it
     * would refer to a different place than the same numbers already stored in the derived layer —
     * a failure that raises no error anywhere and is close to undetectable afterwards.
     */
    suspend fun exportSiteModel(destination: Uri): Outcome = withContext(Dispatchers.IO) {
        val frame = settings.currentSiteFrame() ?: return@withContext Outcome(
            headline = "No coordinate frame recorded",
            detail = listOf(
                "Import a site model first, so the Master knows the site origin. Exporting with " +
                    "a guessed origin would produce coordinates that are not comparable with the " +
                    "ones already stored.",
            ),
            success = false,
        )

        writing(destination) { out ->
            val model = siteModel.export(referenceModelId(), frame)
            siteModel.writeTo(model, out)
            Outcome(
                headline = "Site model ${model.referenceModelId} exported",
                detail = listOf(
                    "${model.buildings.size} buildings, ${model.zones.size} zones, " +
                        "${model.zoneEdges.size} edges",
                    "${model.infrastructureNodes.size} infrastructure nodes, " +
                        "${model.surveyPoints.size} survey points",
                    "${model.fingerprints.size} ground-truth fingerprints; candidates are not " +
                        "exported, because unreviewed survey data shipped inside a file called " +
                        "the site model is how it becomes treated as verified",
                    "Origin ${frame.originLat}, ${frame.originLon}, " +
                        "rotated ${frame.rotationDeg}\u00b0",
                ),
                success = true,
            )
        }
    }

    suspend fun exportDeviceRegistry(destination: Uri): Outcome = withContext(Dispatchers.IO) {
        writing(destination) { out ->
            val registryId = "devices-${dateStamp()}"
            val registry = devices.export(registryId)
            devices.writeTo(registry, out)
            val unattributable = registry.managedDevices.count { it.allIdentifiers.isEmpty() }
            Outcome(
                headline = "${registry.managedDevices.size} devices exported",
                detail = buildList {
                    add("Registry $registryId")
                    add(
                        "The Lab attributes observations only to devices in this file. Anything " +
                            "else it sees stays environmental context and gets no estimate.",
                    )
                    if (unattributable > 0) {
                        add(
                            "$unattributable device(s) carry no identifier and can never be " +
                                "attributed. Add a service UUID or a MAC to each.",
                        )
                    }
                },
                success = true,
            )
        }
    }

    // -- file plumbing ----------------------------------------------------------------------------

    /**
     * Opens [destination] for a full overwrite and hands the stream to [body], which closes it.
     *
     * `"wt"` rather than `"w"`: without truncation, writing a shorter document over a longer one
     * leaves the tail of the old file behind, and the result is a JSON document with trailing
     * rubbish that fails to parse for a reason nobody would guess from the error.
     */
    private suspend fun writing(
        destination: Uri,
        body: suspend (OutputStream) -> Outcome,
    ): Outcome = try {
        val out = context.contentResolver.openOutputStream(destination, "wt")
            ?: throw IOException("the chosen file could not be opened for writing")
        body(out)
    } catch (error: IOException) {
        Outcome("Export failed", listOf(error.message ?: "the file could not be written"), false)
    }

    private suspend fun referenceModelId(): String =
        settings.currentReferenceModelId().ifBlank { "site-${dateStamp()}" }

    private fun dateStamp(): String = Iso8601.utcDateStamp(nowMillis())
}
