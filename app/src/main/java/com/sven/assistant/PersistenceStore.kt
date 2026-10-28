package com.sven.assistant

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal object PersistenceStore {
    private const val PREFS = "sven_local_state"
    private const val MODEL_URI = "model_uri"
    private const val HISTORY = "conversation"
    private const val NOTES = "notes"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveModelUri(context: Context, uri: Uri) {
        prefs(context).edit().putString(MODEL_URI, uri.toString()).apply()
    }

    fun getModelUri(context: Context): Uri? =
        prefs(context).getString(MODEL_URI, null)?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }

    fun saveHistory(context: Context, history: List<Pair<String, String>>) {
        val array = JSONArray()
        history.takeLast(200).forEach { (role, message) ->
            array.put(JSONObject().put("role", role).put("message", message))
        }
        prefs(context).edit().putString(HISTORY, array.toString()).apply()
    }

    fun loadHistory(context: Context): List<Pair<String, String>> {
        val raw = prefs(context).getString(HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val role = item.optString("role")
                val message = item.optString("message")
                if ((role == "user" || role == "assistant") && message.isNotBlank()) {
                    role to message
                } else null
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun addNote(context: Context, note: String): Boolean {
        val clean = note.trim().take(2000)
        if (clean.isBlank()) return false
        val p = prefs(context)
        val array = runCatching {
            JSONArray(p.getString(NOTES, "[]"))
        }.getOrDefault(JSONArray())
        array.put(
            JSONObject()
                .put("text", clean)
                .put("created", System.currentTimeMillis())
        )
        while (array.length() > 300) array.remove(0)
        p.edit().putString(NOTES, array.toString()).apply()
        return true
    }

    @Synchronized
    fun clearNotes(context: Context) {
        prefs(context).edit().remove(NOTES).apply()
    }

    fun getNotes(context: Context): List<String> {
        val array = runCatching {
            JSONArray(prefs(context).getString(NOTES, "[]"))
        }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
        }
    }
}
