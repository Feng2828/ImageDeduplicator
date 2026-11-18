package com.sopro.imagededup.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("image_dedup_settings", Context.MODE_PRIVATE)

    var treeUri: String?
        get() = prefs.getString(KEY_TREE_URI, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_TREE_URI) else putString(KEY_TREE_URI, value)
        }

    var threshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, 4)
        set(value) = prefs.edit { putInt(KEY_THRESHOLD, value) }

    var backupEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKUP, false)
        set(value) = prefs.edit { putBoolean(KEY_BACKUP, value) }

    private companion object {
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_THRESHOLD = "threshold"
        const val KEY_BACKUP = "backup"
    }
}
