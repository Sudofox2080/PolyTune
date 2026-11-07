package com.polytune.core.util

import com.polytune.source.api.LineLyric
import com.polytune.source.api.Lyric

/**
 * LRC 解析。支持一行多时间戳、翻译行（同时间戳的第二段文本）、罗马音（同时间戳第三段）、
 * 以及元信息行（ar/ti/al/by/offset）。
 */
object LrcParser {

    private val TIME = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val META = Regex("^\\[(ar|ti|al|by|offset|re|ve|kana):(.*)]$", RegexOption.IGNORE_CASE)
    private val WORD_BY_WORD = Regex("<\\d{1,2}:\\d{1,2}[.:]\\d{1,3}>")

    fun parse(lrc: String, translationLrc: String? = null, romanLrc: String? = null): Lyric {
        if (lrc.isBlank()) return Lyric(instrumental = true)

        var offset = 0L
        val raw = mutableListOf<Pair<Long, String>>()

        lrc.lineSequence().forEach { line ->
            META.find(line.trim())?.let { m ->
                if (m.groupValues[1].equals("offset", true)) {
                    offset = m.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                return@forEach
            }
            val stamps = TIME.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val text = line.substring(stamps.last().range.last + 1)
                .replace(WORD_BY_WORD, "")
                .trim()
            stamps.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: 0
                val sec = m.groupValues[2].toLongOrNull() ?: 0
                val fracRaw = m.groupValues[3]
                val frac = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100
                    2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                raw += ((min * 60 + sec) * 1000 + frac - offset) to text
            }
        }

        if (raw.isEmpty()) return Lyric(instrumental = true)

        val trans = parseMap(translationLrc)
        val roman = parseMap(romanLrc)

        val lines = raw.sortedBy { it.first }.map { (t, text) ->
            LineLyric(
                timeMs = t,
                text = text,
                translation = trans[t]?.takeIf { it.isNotBlank() && it != text },
                romanization = roman[t]?.takeIf { it.isNotBlank() && it != text },
            )
        }
        return Lyric(lines = lines, instrumental = lines.all { it.text.isBlank() })
    }

    private fun parseMap(lrc: String?): Map<Long, String> {
        if (lrc.isNullOrBlank()) return emptyMap()
        val out = mutableMapOf<Long, String>()
        lrc.lineSequence().forEach { line ->
            val stamps = TIME.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val text = line.substring(stamps.last().range.last + 1).trim()
            stamps.forEach { m ->
                val sec = ((m.groupValues[1].toLongOrNull() ?: 0) * 60 + (m.groupValues[2].toLongOrNull() ?: 0))
                val fracRaw = m.groupValues[3]
                val frac = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100
                    2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                out[(sec * 1000 + frac)] = text
            }
        }
        return out
    }

    /** 网易式逐行 JSON（{t: ms, c: [{tx: 文本}]}）解析，供网络层复用。 */
    fun fromLrcJson(jsonLines: List<Pair<Long, String>>): Lyric {
        val sorted = jsonLines.sortedBy { it.first }
        return Lyric(
            lines = sorted.map { (t, s) -> LineLyric(t, s) },
            instrumental = sorted.all { it.second.isBlank() },
        )
    }
}
