package com.polytune.core.util

import com.polytune.source.api.LineLyric
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 歌词随音频落盘的旁边车文件。
 *
 * 下载时把歌词写进 `<音频名>.lrc.json`，离线播放就直接读它；这样断网时也不会出现
 * 「歌在本地、歌词却显示暂无」。文件名带完整音频名（含扩展名）是为了同一目录下
 * 同名不同格式（flac/mp3）各自有自己的歌词，不会互相覆盖。
 */
object LyricStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(LineLyric.serializer())

    fun sidecarFor(audio: File): File = File(audio.absolutePath + ".lrc.json")

    fun save(audio: File, lines: List<LineLyric>) {
        if (lines.isEmpty()) return
        runCatching {
            sidecarFor(audio).writeText(json.encodeToString(serializer, lines))
        }
    }

    fun load(audio: File): List<LineLyric> {
        val file = sidecarFor(audio)
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrDefault(emptyList())
    }
}
