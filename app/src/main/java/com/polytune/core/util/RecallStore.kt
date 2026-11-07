package com.polytune.core.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 搜索历史，轻量落盘，不上 Room。 */
class RecallStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("polytune_recall", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(String.serializer())

    private val _keywords = MutableStateFlow(loadKeywords())
    val keywords: StateFlow<List<String>> = _keywords

    fun putKeyword(k: String) {
        val t = k.trim()
        if (t.isEmpty()) return
        val next = (listOf(t) + _keywords.value.filter { it != t }).take(20)
        _keywords.value = next
        prefs.edit().putString(KEY, json.encodeToString(listSerializer, next)).apply()
    }

    fun clearKeywords() {
        _keywords.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private fun loadKeywords(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY = "keywords"
        @Volatile private var INSTANCE: RecallStore? = null
        fun init(c: Context) { if (INSTANCE == null) synchronized(this) { if (INSTANCE == null) INSTANCE = RecallStore(c) } }
        fun get(): RecallStore = INSTANCE!!
    }
}
