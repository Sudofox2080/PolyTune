package com.polytune.core.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 轻量 JSON 取值工具。各家接口字段漂移频繁，用路径取值比写满 data class 更抗变化。
 * 路径形如 "data.song.list" 或 "data.list" / "data[0].v_play_url"。
 */
object Jp {

    fun root(json: String): JsonElement? = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(json)
    }.getOrNull()

    fun obj(e: JsonElement?): JsonObject? = e as? JsonObject
    fun arr(e: JsonElement?): JsonArray? = e as? JsonArray

    fun get(e: JsonElement?, path: String): JsonElement? {
        if (e == null) return null
        var cur: JsonElement? = e
        if (path.isBlank()) return cur
        for (segRaw in path.split('.')) {
            var seg = segRaw
            while (seg.isNotEmpty()) {
                val bracket = seg.indexOf('[')
                if (bracket == 0) {
                    val close = seg.indexOf(']')
                    if (close < 0) return null
                    val idx = seg.substring(1, close).toIntOrNull() ?: return null
                    cur = (cur as? JsonArray)?.getOrNull(idx) ?: return null
                    seg = seg.substring(close + 1)
                } else {
                    val key = if (bracket > 0) seg.substring(0, bracket) else seg
                    cur = (cur as? JsonObject)?.get(key) ?: return null
                    seg = if (bracket > 0) seg.substring(bracket) else ""
                }
            }
        }
        return cur.takeIf { it !is JsonNull }
    }

    fun str(e: JsonElement?, path: String): String? =
        (get(e, path) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    fun str(e: JsonElement?, vararg paths: String): String? =
        paths.firstNotNullOfOrNull { str(e, it) }

    fun long(e: JsonElement?, path: String): Long? = (get(e, path) as? JsonPrimitive)?.longOrNull

    fun double(e: JsonElement?, path: String): Double? = (get(e, path) as? JsonPrimitive)?.doubleOrNull

    fun int(e: JsonElement?, path: String): Int? = long(e, path)?.toInt()

    fun bool(e: JsonElement?, path: String): Boolean? = (get(e, path) as? JsonPrimitive)?.booleanOrNull

    fun array(e: JsonElement?, path: String): List<JsonElement> = arr(get(e, path))?.toList() ?: emptyList()

    fun objects(e: JsonElement?, path: String): List<JsonObject> =
        array(e, path).mapNotNull { it as? JsonObject }

    /** 嵌套数组里挑第一个非空数组，用于各源字段位置不一致时兜底。 */
    fun firstArray(e: JsonElement?, vararg paths: String): List<JsonElement> =
        paths.firstNotNullOfOrNull { p -> array(e, p).takeIf { it.isNotEmpty() } } ?: emptyList()
}
