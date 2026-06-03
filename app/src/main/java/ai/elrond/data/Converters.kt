package ai.elrond.data

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json

/** Room type converters. */
class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else Json.decodeFromString(value)
}
