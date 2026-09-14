package com.rfmapper.data.room

import androidx.room.TypeConverter
import com.rfmapper.core.model.RfMapperJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Storage encodings for the composite values on the entities.
 *
 * Maps and lists are stored as JSON text. That is a deliberate limit on what may be stored this
 * way: anything the app needs to *query* is a real column (see the denormalised `session_id` and
 * `sample_kind` on `raw_observation`), because SQLite cannot index inside a text blob.
 */
class Converters {

    private val stringMap = MapSerializer(String.serializer(), String.serializer())
    private val stringList = ListSerializer(String.serializer())

    @TypeConverter
    fun metadataToJson(value: Map<String, String>): String =
        RfMapperJson.compact.encodeToString(stringMap, value.toSortedMap())

    @TypeConverter
    fun jsonToMetadata(value: String): Map<String, String> =
        if (value.isBlank()) emptyMap() else RfMapperJson.compact.decodeFromString(stringMap, value)

    @TypeConverter
    fun stringsToJson(value: List<String>): String =
        RfMapperJson.compact.encodeToString(stringList, value)

    @TypeConverter
    fun jsonToStrings(value: String): List<String> =
        if (value.isBlank()) emptyList() else RfMapperJson.compact.decodeFromString(stringList, value)
}
