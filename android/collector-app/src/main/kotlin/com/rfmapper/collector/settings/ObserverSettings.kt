package com.rfmapper.collector.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rfmapper.core.radio.ScanProfile
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.observerStore: DataStore<Preferences> by preferencesDataStore("observer")

/**
 * The Collector's own identity and configuration.
 *
 * [installationId] is generated once and never regenerated. It is what lets the Master distinguish
 * "OBS-04 reinstalled the app" from "two devices are both claiming to be OBS-04" — a distinction
 * that matters because the second case silently mixes two chipsets' RSSI characteristics into one
 * observer's calibration, and no amount of downstream analysis can separate them afterwards.
 *
 * The Storage Access Framework tree grant is persisted here too. The grant survives reboots only if
 * its URI is remembered, and an export that silently fails because the app forgot where to write is
 * the worst possible outcome at the end of a long field session (`docs/09`, §5).
 */
class ObserverSettings(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.observerStore)

    data class Snapshot(
        val observerId: String?,
        val friendlyName: String,
        val buildingId: String?,
        val defaultZoneId: String?,
        val scanProfile: ScanProfile,
        val installationId: String,
        val exportTreeUri: String?,
        val fixedObserver: Boolean,
        val xCoordinate: Double?,
        val yCoordinate: Double?,
        val notes: String?,
    ) {
        /** Collection is refused until an observer id exists: an unattributed row is unusable. */
        val isConfigured: Boolean get() = !observerId.isNullOrBlank()
    }

    val snapshots: Flow<Snapshot> = store.data.map { it.toSnapshot() }

    suspend fun snapshot(): Snapshot = snapshots.first()

    /**
     * Reads the installation id, creating it on first use.
     *
     * Done inside `edit` so two concurrent first reads cannot mint two different ids — which would
     * make the very field that exists to detect a duplicated observer identity the thing that was
     * duplicated.
     */
    suspend fun installationId(): String =
        store.edit { prefs ->
            if (prefs[KEY_INSTALLATION_ID].isNullOrBlank()) {
                prefs[KEY_INSTALLATION_ID] = UUID.randomUUID().toString()
            }
        }[KEY_INSTALLATION_ID]!!

    suspend fun setIdentity(
        observerId: String,
        friendlyName: String,
        buildingId: String?,
        defaultZoneId: String?,
        notes: String? = null,
    ) {
        store.edit { prefs ->
            prefs[KEY_OBSERVER_ID] = observerId.trim()
            prefs[KEY_FRIENDLY_NAME] = friendlyName.trim().ifBlank { observerId.trim() }
            buildingId?.trim()?.ifBlank { null }?.let { prefs[KEY_BUILDING_ID] = it }
                ?: prefs.remove(KEY_BUILDING_ID)
            defaultZoneId?.trim()?.ifBlank { null }?.let { prefs[KEY_ZONE_ID] = it }
                ?: prefs.remove(KEY_ZONE_ID)
            notes?.trim()?.ifBlank { null }?.let { prefs[KEY_NOTES] = it } ?: prefs.remove(KEY_NOTES)
        }
    }

    suspend fun setScanProfile(profile: ScanProfile) {
        store.edit { it[KEY_SCAN_PROFILE] = profile.name }
    }

    suspend fun setExportTree(uri: String?) {
        store.edit { prefs ->
            if (uri == null) prefs.remove(KEY_EXPORT_TREE) else prefs[KEY_EXPORT_TREE] = uri
        }
    }

    /**
     * Declares this installation a fixed observer at a measured position.
     *
     * Gated behind an explicit action because a fixed observer's samples become reference-quality
     * evidence in multi-observer fusion. Setting it for a phone that merely happens to be sitting
     * somewhere would give a guess the weight of a survey.
     */
    suspend fun setFixedObserver(x: Double?, y: Double?) {
        store.edit { prefs ->
            if (x == null || y == null) {
                prefs[KEY_FIXED_OBSERVER] = false
                prefs.remove(KEY_X)
                prefs.remove(KEY_Y)
            } else {
                prefs[KEY_FIXED_OBSERVER] = true
                prefs[KEY_X] = x
                prefs[KEY_Y] = y
            }
        }
    }

    private fun Preferences.toSnapshot() = Snapshot(
        observerId = this[KEY_OBSERVER_ID],
        friendlyName = this[KEY_FRIENDLY_NAME] ?: this[KEY_OBSERVER_ID] ?: "",
        buildingId = this[KEY_BUILDING_ID],
        defaultZoneId = this[KEY_ZONE_ID],
        scanProfile = ScanProfile.fromNameOrDefault(this[KEY_SCAN_PROFILE]),
        installationId = this[KEY_INSTALLATION_ID] ?: "",
        exportTreeUri = this[KEY_EXPORT_TREE],
        fixedObserver = this[KEY_FIXED_OBSERVER] ?: false,
        xCoordinate = this[KEY_X],
        yCoordinate = this[KEY_Y],
        notes = this[KEY_NOTES],
    )

    private companion object {
        val KEY_OBSERVER_ID = stringPreferencesKey("observer_id")
        val KEY_FRIENDLY_NAME = stringPreferencesKey("friendly_name")
        val KEY_BUILDING_ID = stringPreferencesKey("building_id")
        val KEY_ZONE_ID = stringPreferencesKey("default_zone_id")
        val KEY_SCAN_PROFILE = stringPreferencesKey("scan_profile")
        val KEY_INSTALLATION_ID = stringPreferencesKey("installation_id")
        val KEY_EXPORT_TREE = stringPreferencesKey("export_tree_uri")
        val KEY_FIXED_OBSERVER = booleanPreferencesKey("fixed_observer")
        val KEY_X = doublePreferencesKey("x_coordinate")
        val KEY_Y = doublePreferencesKey("y_coordinate")
        val KEY_NOTES = stringPreferencesKey("notes")
    }
}
