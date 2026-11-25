package com.lumina.gallery.data

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("lumina_prefs", Context.MODE_PRIVATE)

    var sortField: String
        get() = sp.getString("sortField", "MODIFIED") ?: "MODIFIED"
        set(v) { sp.edit().putString("sortField", v).apply() }

    var sortAsc: Boolean
        get() = sp.getBoolean("sortAsc", false)
        set(v) { sp.edit().putBoolean("sortAsc", v).apply() }

    var viewList: Boolean
        get() = sp.getBoolean("viewList", false)
        set(v) { sp.edit().putBoolean("viewList", v).apply() }

    var columns: Int
        get() = sp.getInt("columns", 2)
        set(v) { sp.edit().putInt("columns", v).apply() }

    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) { sp.edit().putString("geminiKey", v.trim()).apply() }

    var randomSeed: Long
        get() = sp.getLong("randomSeed", 17L)
        set(v) { sp.edit().putLong("randomSeed", v).apply() }

    var vaultSalt: String
        get() = sp.getString("vaultSalt", "") ?: ""
        set(v) { sp.edit().putString("vaultSalt", v).apply() }

    var vaultHash: String
        get() = sp.getString("vaultHash", "") ?: ""
        set(v) { sp.edit().putString("vaultHash", v).apply() }

    var pinned: Set<Long>
        get() = longs("pinned")
        set(v) = putLongs("pinned", v)

    var hidden: Set<Long>
        get() = longs("hidden")
        set(v) = putLongs("hidden", v)

    var emptyFolders: Set<String>
        get() = sp.getStringSet("emptyFolders", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("emptyFolders", v).apply() }

    private fun longs(key: String): Set<Long> =
        (sp.getStringSet(key, emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()

    private fun putLongs(key: String, v: Set<Long>) {
        sp.edit().putStringSet(key, v.map { it.toString() }.toSet()).apply()
    }
}
