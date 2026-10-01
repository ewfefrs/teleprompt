package com.example.teleprompter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedText(val id: Long, val title: String, val body: String, val updatedAt: Long)

class TextRepository(context: Context) {

    private val appContext = context.applicationContext

    fun list(): List<SavedText> {
        val raw = Prefs.getString(appContext, Prefs.KEY_TEXTS, "")
        if (raw.isEmpty()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = ArrayList<SavedText>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            result.add(
                SavedText(
                    id = o.getLong("id"),
                    title = o.getString("title"),
                    body = o.getString("body"),
                    updatedAt = o.optLong("updatedAt", 0L)
                )
            )
        }
        return result.sortedByDescending { it.updatedAt }
    }

    fun get(id: Long): SavedText? = list().firstOrNull { it.id == id }

    fun save(id: Long?, title: String, body: String): SavedText {
        val items = list().toMutableList()
        val now = System.currentTimeMillis()
        val item = SavedText(id ?: now, title.ifBlank { defaultTitle(body) }, body, now)
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) items[index] = item else items.add(item)
        persist(items)
        return item
    }

    fun delete(id: Long) {
        persist(list().filter { it.id != id })
    }

    private fun persist(items: List<SavedText>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("body", item.body)
                    .put("updatedAt", item.updatedAt)
            )
        }
        Prefs.putString(appContext, Prefs.KEY_TEXTS, array.toString())
    }

    private fun defaultTitle(body: String): String {
        val firstLine = body.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        return if (firstLine.length <= 40) firstLine else firstLine.substring(0, 40)
    }
}
