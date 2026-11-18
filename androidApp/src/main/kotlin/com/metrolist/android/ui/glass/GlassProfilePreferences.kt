package com.metrolist.android.ui.glass

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.android.utils.DEFAULT_PROFILE_ID
import com.metrolist.android.utils.isProfileIdSafe
import timber.log.Timber

/** Profile-owned Glass files, seeded once from the former global configuration. */
internal object GlassProfilePreferences {
    const val LEGACY_NAME = "nzik_square_glass_v2"
    internal const val MIGRATION_NAME = "nzik_square_glass_migration_v1"
    private const val SNAPSHOT_READY = "snapshot_ready"
    private const val VALUE_PREFIX = "value:"
    private const val TAG = "GlassProfilePreferences"

    fun fileName(profileId: String): String {
        require(profileId.isNotBlank() && isProfileIdSafe(profileId)) { "Invalid Glass profile ID" }
        return if (profileId == DEFAULT_PROFILE_ID) LEGACY_NAME else "${LEGACY_NAME}_$profileId"
    }

    @Synchronized
    fun preferences(context: Context, profileId: String): SharedPreferences {
        val targetName = fileName(profileId)
        val app = context.applicationContext
        val migration = app.getSharedPreferences(MIGRATION_NAME, Context.MODE_PRIVATE)
        val snapshotSaved = if (!migration.getBoolean(SNAPSHOT_READY, false)) {
            val legacy = app.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
            migration.edit().apply {
                migration.all.keys.filter { it.startsWith(VALUE_PREFIX) }.forEach { remove(it) }
                legacy.all.forEach { (key, value) -> putValue(VALUE_PREFIX + key, value) }
                putBoolean(SNAPSHOT_READY, true)
            }.commitMigration(migration, SNAPSHOT_READY, "Could not save the legacy Glass snapshot")
        } else true
        val target = app.getSharedPreferences(targetName, Context.MODE_PRIVATE)
        val initializedKey = "profile:$profileId"
        if (profileId != DEFAULT_PROFILE_ID && !migration.getBoolean(initializedKey, false)) {
            val seeded = target.edit().apply {
                migration.all.forEach { (key, value) ->
                    if (key.startsWith(VALUE_PREFIX)) {
                        val setting = key.removePrefix(VALUE_PREFIX)
                        if (!target.contains(setting)) putValue(setting, value)
                    }
                }
            }.commit()
            if (!seeded) Timber.tag(TAG).w("Could not migrate Glass settings; retrying on next access")
            else if (snapshotSaved) migration.edit().putBoolean(initializedKey, true)
                .commitMigration(migration, initializedKey, "Could not save Glass migration completion")
        }
        return target
    }

    /** Keep the migration marker so reset or a reused ID cannot resurrect old settings. */
    @Synchronized
    fun delete(context: Context, profileId: String) {
        // Finish pending store writes before removing its XML.
        check(preferences(context, profileId).edit().commit()) { "Could not flush Glass settings before deletion" }
        val app = context.applicationContext
        val migration = app.getSharedPreferences(MIGRATION_NAME, Context.MODE_PRIVATE)
        check(migration.getBoolean(SNAPSHOT_READY, false) &&
            (profileId == DEFAULT_PROFILE_ID || migration.getBoolean("profile:$profileId", false))) {
            "Could not complete Glass migration before deletion"
        }
        check(app.deleteSharedPreferences(fileName(profileId))) {
            "Could not delete Glass settings"
        }
    }

    private fun SharedPreferences.Editor.commitMigration(
        preferences: SharedPreferences,
        marker: String,
        failureMessage: String,
    ): Boolean {
        if (commit()) return true
        // A failed commit still changes memory; leave the migration retryable in this process too.
        preferences.edit().remove(marker).commit()
        Timber.tag(TAG).w("%s; retrying on next access", failureMessage)
        return false
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}
