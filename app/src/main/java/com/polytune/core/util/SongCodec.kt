package com.polytune.core.util

import com.polytune.source.api.Song
import kotlinx.serialization.json.Json

/**
 * 把 Song 序列化成 JSON 存进下载任务表。用序列化而不是手写 JSON，
 * 是因为 Song 后续加字段时旧任务仍能读出来（ignoreUnknownKeys + 默认值）。
 */
object SongCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(song: Song): String = json.encodeToString(Song.serializer(), song)

    fun decode(raw: String): Song = json.decodeFromString(Song.serializer(), raw)
}
