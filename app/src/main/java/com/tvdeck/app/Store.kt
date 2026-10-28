package com.tvdeck.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class SavedDevice(val name: String, val host: String, val port: Int)

/** App-wide messages (shown as a snackbar) and things shared into the app from other apps. */
object Shared {
    var message by mutableStateOf<String?>(null)
    var pendingLink by mutableStateOf<String?>(null)
    var pendingText by mutableStateOf<String?>(null)
    var currentTab by mutableStateOf(0)
    var pendingFiles by mutableStateOf<List<Uri>>(emptyList())
    fun say(m: String) { message = m }
}

object Store {
    val devices = mutableStateListOf<SavedDevice>()
    val history = mutableStateListOf<String>()
    val macros = mutableStateListOf<Pair<String, String>>()
    /** Apps the debloater turned off: package to method ("disabled" or "removed"). */
    val removed = mutableStateListOf<Pair<String, String>>()
    /** Apps pinned to the Remote tab for one-tap launching. */
    val pins = mutableStateListOf<String>()
    private var prefs: SharedPreferences? = null

    fun load(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("tvdeck", Context.MODE_PRIVATE)
        prefs = p
        p.getString("devices", "")?.lines()?.forEach { l ->
            val a = l.split("\t")
            if (a.size >= 3) devices.add(SavedDevice(a[0], a[1], a[2].toIntOrNull() ?: 5555))
        }
        p.getString("history", "")?.lines()?.filter { it.isNotBlank() }?.let { history.addAll(it) }
        p.getString("macros", "")?.lines()?.forEach { l ->
            val a = l.split("\t", limit = 2)
            if (a.size == 2) macros.add(a[0] to a[1])
        }
        p.getString("pins", "")?.lines()?.filter { it.isNotBlank() }?.let { pins.addAll(it) }
        p.getString("removed", "")?.lines()?.forEach { l ->
            val a = l.split("\t", limit = 2)
            if (a.size == 2 && a[0].isNotBlank()) removed.add(a[0] to a[1])
        }
    }

    var lastHost: String
        get() = prefs?.getString("last", "") ?: ""
        set(v) { prefs?.edit()?.putString("last", v)?.apply() }

    fun getPref(key: String, def: String): String = prefs?.getString("pref_$key", def) ?: def
    fun setPref(key: String, value: String) { prefs?.edit()?.putString("pref_$key", value)?.apply() }

    private fun clean(s: String) = s.replace("\t", " ").replace("\n", " ").trim()

    private fun persist() {
        prefs?.edit()
            ?.putString("devices", devices.joinToString("\n") { clean(it.name) + "\t" + it.host + "\t" + it.port })
            ?.putString("history", history.joinToString("\n") { clean(it) })
            ?.putString("macros", macros.joinToString("\n") { clean(it.first) + "\t" + clean(it.second) })
            ?.putString("pins", pins.joinToString("\n"))
            ?.putString("removed", removed.joinToString("\n") { clean(it.first) + "\t" + clean(it.second) })
            ?.apply()
    }

    fun upsert(d: SavedDevice) {
        val i = devices.indexOfFirst { it.host == d.host && it.port == d.port }
        if (i >= 0) devices[i] = devices[i].copy() else devices.add(d)
        persist()
    }

    fun rename(d: SavedDevice, name: String) {
        val i = devices.indexOf(d)
        if (i >= 0) { devices[i] = d.copy(name = name.trim()); persist() }
    }

    fun remove(d: SavedDevice) { devices.remove(d); persist() }

    fun addHistory(cmd: String) {
        val c = clean(cmd)
        if (c.isEmpty()) return
        history.remove(c)
        history.add(0, c)
        while (history.size > 12) history.removeAt(history.size - 1)
        persist()
    }

    fun addMacro(name: String, cmd: String) {
        macros.removeAll { it.first == clean(name) }
        macros.add(clean(name) to clean(cmd))
        persist()
    }

    fun removeMacro(m: Pair<String, String>) { macros.remove(m); persist() }

    fun togglePin(pkg: String) {
        if (pins.contains(pkg)) pins.remove(pkg) else pins.add(pkg)
        persist()
    }

    fun markRemoved(pkg: String, method: String) {
        removed.removeAll { it.first == pkg }
        removed.add(pkg to method)
        persist()
    }

    fun unmarkRemoved(pkg: String) {
        removed.removeAll { it.first == pkg }
        persist()
    }
}
