package com.rfmapper.master.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.masterStore: DataStore<Preferences> by preferencesDataStore(name = "rfmapper_master")

/**
 * The Master's own configuration.
 *
 * [operator] is the smallest piece here and the most consequential: it is stamped on every import
 * and every fingerprint promotion. A promotion with no name attached is indistinguishable from one
 * nobody made, which is precisely the distinction between calibration data and a guess
 * (`docs/11-ground-truth-and-calibration-procedure.md`).
 */
class MasterSettings(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.masterStore)

    val operator: Flow<String> = store.data.map { it[OPERATOR].orEmpty() }

    /**
     * Which derived generation the UI displays. Held here rather than derived from "most recently
     * imported", because a newly arrived algorithm version becoming the visible truth without
     * anybody choosing it is how an unreviewed pipeline change reaches a decision-maker.
     */
    val activeAlgorithmVersion: Flow<String?> = store.data.map { it[ACTIVE_VERSION] }

    val siteFolderUri: Flow<String?> = store.data.map { it[SITE_FOLDER] }

    val referenceModelId: Flow<String> = store.data.map { it[REFERENCE_MODEL].orEmpty() }

    suspend fun currentOperator(): String = operator.first()

    suspend fun setOperator(value: String) {
        store.edit { it[OPERATOR] = value.trim() }
    }

    suspend fun setActiveAlgorithmVersion(value: String?) {
        store.edit { prefs ->
            if (value == null) prefs.remove(ACTIVE_VERSION) else prefs[ACTIVE_VERSION] = value
        }
    }

    suspend fun setSiteFolderUri(value: String?) {
        store.edit { prefs ->
            if (value == null) prefs.remove(SITE_FOLDER) else prefs[SITE_FOLDER] = value
        }
    }

    suspend fun setReferenceModelId(value: String) {
        store.edit { it[REFERENCE_MODEL] = value.trim() }
    }

    private companion object {
        val OPERATOR = stringPreferencesKey("operator")
        val ACTIVE_VERSION = stringPreferencesKey("active_algorithm_version")
        val SITE_FOLDER = stringPreferencesKey("site_folder_uri")
        val REFERENCE_MODEL = stringPreferencesKey("reference_model_id")
    }
}
