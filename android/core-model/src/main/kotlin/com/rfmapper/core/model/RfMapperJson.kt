package com.rfmapper.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The canonical JSON configuration for every package this system reads or writes.
 *
 * `ignoreUnknownKeys = true` implements the schema's forward-compatibility rule: a minor version may
 * add optional fields, and an older build must accept such a package rather than refuse it. Rejecting
 * an unknown *major* version is a separate, explicit check ([SchemaVersion.isReadable]).
 *
 * `explicitNulls = true` keeps every nullable field present in the output, so the JSON form of an
 * observation always shows all 29 fields. That makes a package readable without consulting the
 * schema to discover which fields were merely omitted.
 */
object RfMapperJson {

    val compact: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @OptIn(ExperimentalSerializationApi::class)
    val pretty: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}
