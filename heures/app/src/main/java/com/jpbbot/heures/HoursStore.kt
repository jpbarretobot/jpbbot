package com.jpbbot.heures

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class HoursStore(context: Context) {

    private val prefs = context.getSharedPreferences("heures", Context.MODE_PRIVATE)

    fun runningStart(): Long? =
        if (prefs.contains(KEY_RUNNING)) prefs.getLong(KEY_RUNNING, 0L) else null

    /** Returns false when a session is already running. */
    fun start(now: Long = System.currentTimeMillis()): Boolean {
        if (runningStart() != null) return false
        prefs.edit().putLong(KEY_RUNNING, now).apply()
        return true
    }

    /** Ends the running session and saves it. Returns null when nothing is running. */
    fun finish(now: Long = System.currentTimeMillis()): WorkSession? {
        val start = runningStart() ?: return null
        val session = WorkSession(start = start, end = maxOf(now, start))
        prefs.edit()
            .remove(KEY_RUNNING)
            .putString(KEY_SESSIONS, encode(sessions() + session))
            .apply()
        return session
    }

    fun sessions(): List<WorkSession> =
        prefs.getString(KEY_SESSIONS, null)?.let { decode(it) } ?: emptyList()

    fun add(session: WorkSession) = save(sessions() + session)

    fun delete(session: WorkSession) = save(sessions() - session)

    private fun save(sessions: List<WorkSession>) {
        prefs.edit().putString(KEY_SESSIONS, encode(sessions)).apply()
    }

    private fun encode(sessions: List<WorkSession>): String =
        JSONArray().apply {
            sessions.forEach { put(JSONObject().put("start", it.start).put("end", it.end)) }
        }.toString()

    private fun decode(text: String): List<WorkSession> {
        val array = JSONArray(text)
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            WorkSession(start = item.getLong("start"), end = item.getLong("end"))
        }
    }

    private companion object {
        const val KEY_RUNNING = "running_start"
        const val KEY_SESSIONS = "sessions"
    }
}
