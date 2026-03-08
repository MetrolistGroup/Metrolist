@file:Suppress("ktlint:standard:function-naming")

package com.metrolist.shared.settings

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class LinuxCredentialRecord(
    val payload: String,
    val modified: Long,
    val path: String,
) {
    val hasValues = Properties().apply { payload.byteInputStream().use { load(it) } }.isNotEmpty()
}

internal fun selectLinuxCredentialRecord(records: Collection<LinuxCredentialRecord>): LinuxCredentialRecord? =
    records.maxWithOrNull(compareBy<LinuxCredentialRecord> { it.hasValues }.thenBy { it.modified }.thenBy { it.path })

internal data class LinuxLockedCredentialItem(
    val modified: Long,
    val path: String,
)

internal fun <T> loadLinuxCredentialSecret(
    item: LinuxLockedCredentialItem,
    isLocked: Boolean,
    lockedItems: MutableCollection<LinuxLockedCredentialItem>,
    loadSecret: () -> T?,
): T? {
    val secret = if (isLocked) null else loadSecret()
    if (secret == null) lockedItems += item
    return secret
}

internal sealed interface LinuxCredentialSelection {
    data class Ready(
        val record: LinuxCredentialRecord?,
    ) : LinuxCredentialSelection

    data object Unlock : LinuxCredentialSelection

    data object Unreadable : LinuxCredentialSelection
}

internal fun resolveLinuxCredentialSelection(
    records: Collection<LinuxCredentialRecord>,
    lockedItems: Collection<LinuxLockedCredentialItem>,
    hasMatches: Boolean,
    unlockAttempted: Boolean,
): LinuxCredentialSelection {
    val record = selectLinuxCredentialRecord(records)
    val possiblyOutranked =
        lockedItems.any { item ->
            record == null || !record.hasValues || item.modified <= 0 || record.modified <= 0 ||
                item.modified > record.modified || (item.modified == record.modified && item.path > record.path)
        }
    return when {
        possiblyOutranked -> if (unlockAttempted) LinuxCredentialSelection.Unreadable else LinuxCredentialSelection.Unlock
        record == null && hasMatches -> LinuxCredentialSelection.Unreadable
        else -> LinuxCredentialSelection.Ready(record)
    }
}

internal fun parseLinuxCredentialRecord(
    payload: String?,
    modified: Long,
    path: String,
): LinuxCredentialRecord? =
    try {
        payload?.let { LinuxCredentialRecord(it, modified, path) }
    } catch (_: IllegalArgumentException) {
        null
    }

internal fun runLinuxCredentialCommand(
    command: List<String>,
    input: String,
): CredentialCommandResult {
    val operation = command.getOrNull(1)
    if (operation != "store" && operation != "lookup") return runCredentialCommand(command, input)
    // secret-tool accepts at most 8192 stdin bytes, even when it reports a successful store.
    return try {
        LinuxSecretServiceWriter.execute(command.drop(if (operation == "store") 3 else 2), if (operation == "store") input else null)
    } catch (error: LinkageError) {
        throw IllegalStateException("libsecret is unavailable", error)
    }
}

private object LinuxSecretServiceWriter {
    private const val SEARCH_ALL = 1 shl 1
    private const val SEARCH_UNLOCK = 1 shl 2
    private const val SEARCH_LOAD_SECRETS = 1 shl 3

    private interface Secret : Library {
        fun secret_service_search_sync(
            service: Pointer?,
            schema: Pointer?,
            attributes: Pointer,
            flags: Int,
            cancellable: Pointer,
            error: PointerByReference,
        ): Pointer?

        fun secret_item_set_secret_sync(
            item: Pointer,
            value: Pointer,
            cancellable: Pointer,
            error: PointerByReference,
        ): Int

        fun secret_value_new(
            value: String,
            length: NativeLong,
            contentType: String,
        ): Pointer

        fun secret_value_unref(value: Pointer)

        fun secret_item_get_secret(item: Pointer): Pointer?

        fun secret_item_get_locked(item: Pointer): Int

        fun secret_item_get_modified(item: Pointer): Long

        fun secret_value_get_text(value: Pointer): String?

        fun secret_password_storev_sync(
            schema: Pointer?,
            attributes: Pointer,
            collection: String?,
            label: String,
            password: String,
            cancellable: Pointer,
            error: PointerByReference,
        ): Int
    }

    private interface Glib : Library {
        fun g_hash_table_new_full(
            hash: Pointer,
            equal: Pointer,
            freeKey: Pointer,
            freeValue: Pointer,
        ): Pointer

        fun g_hash_table_insert(
            table: Pointer,
            key: Pointer,
            value: Pointer,
        ): Int

        fun g_hash_table_unref(table: Pointer)

        fun g_strdup(value: String): Pointer

        fun g_error_free(error: Pointer)

        fun g_list_free(list: Pointer)
    }

    private interface Gio : Library {
        fun g_cancellable_new(): Pointer

        fun g_cancellable_cancel(cancellable: Pointer)

        fun g_dbus_proxy_get_object_path(proxy: Pointer): String
    }

    private interface Gobject : Library {
        fun g_object_unref(value: Pointer)
    }

    private val secret = Native.load("secret-1", Secret::class.java)
    private val glib = Native.load("glib-2.0", Glib::class.java)
    private val gio = Native.load("gio-2.0", Gio::class.java)
    private val gobject = Native.load("gobject-2.0", Gobject::class.java)
    private val glibFunctions = NativeLibrary.getInstance("glib-2.0")
    private val timeouts =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "DesktopCredentialTimeout").apply { isDaemon = true }
        }

    fun execute(
        attributes: List<String>,
        payload: String?,
    ): CredentialCommandResult {
        val deadline = credentialDeadlineNanos()
        check(deadline > System.nanoTime()) { "Credential service timed out" }
        val table =
            glib.g_hash_table_new_full(
                glibFunctions.getFunction("g_str_hash"),
                glibFunctions.getFunction("g_str_equal"),
                glibFunctions.getFunction("g_free"),
                glibFunctions.getFunction("g_free"),
            )
        val cancellable = gio.g_cancellable_new()
        val cancellationLock = Any()
        var finished = false
        val timeout =
            timeouts.schedule(
                { synchronized(cancellationLock) { if (!finished) gio.g_cancellable_cancel(cancellable) } },
                (deadline - System.nanoTime()).coerceAtLeast(0L),
                TimeUnit.NANOSECONDS,
            )
        val error = PointerByReference()
        var items: Pointer? = null
        val itemPointers = mutableListOf<Pointer>()
        var value: Pointer? = null
        return try {
            attributes.chunked(2).forEach { (key, value) ->
                glib.g_hash_table_insert(table, glib.g_strdup(key), glib.g_strdup(value))
            }
            var readable = emptyMap<LinuxCredentialRecord, Pointer>()
            var selection: LinuxCredentialSelection = LinuxCredentialSelection.Unreadable
            for (flags in listOf(SEARCH_ALL or SEARCH_LOAD_SECRETS, SEARCH_ALL or SEARCH_UNLOCK or SEARCH_LOAD_SECRETS)) {
                itemPointers.forEach(gobject::g_object_unref)
                itemPointers.clear()
                items?.let(glib::g_list_free)
                items = null
                items = secret.secret_service_search_sync(null, null, table, flags, cancellable, error)
                var node = items
                while (node != null) {
                    itemPointers += node.getPointer(0)
                    node = node.getPointer(Native.POINTER_SIZE.toLong())
                }
                if (error.value != null) return CredentialCommandResult(1, "", "")
                val lockedItems = mutableListOf<LinuxLockedCredentialItem>()
                readable =
                    itemPointers
                        .mapNotNull { pointer ->
                            val modified = secret.secret_item_get_modified(pointer)
                            val path = gio.g_dbus_proxy_get_object_path(pointer)
                            val stored =
                                loadLinuxCredentialSecret(
                                    LinuxLockedCredentialItem(modified, path),
                                    secret.secret_item_get_locked(pointer) != 0,
                                    lockedItems,
                                ) { secret.secret_item_get_secret(pointer) } ?: return@mapNotNull null
                            try {
                                val record =
                                    parseLinuxCredentialRecord(
                                        secret.secret_value_get_text(stored),
                                        modified,
                                        path,
                                    ) ?: return@mapNotNull null
                                record to pointer
                            } finally {
                                secret.secret_value_unref(stored)
                            }
                        }.toMap()
                selection =
                    resolveLinuxCredentialSelection(
                        readable.keys,
                        lockedItems,
                        itemPointers.isNotEmpty(),
                        flags and SEARCH_UNLOCK != 0,
                    )
                if (selection != LinuxCredentialSelection.Unlock) break
            }
            val record =
                when (val resolved = selection) {
                    is LinuxCredentialSelection.Ready -> resolved.record
                    else -> return CredentialCommandResult(1, "", "Credential items are unreadable")
                }
            if (payload == null) return CredentialCommandResult(if (record == null) 1 else 0, record?.payload.orEmpty(), "")
            val saved =
                if (record != null) {
                    val replacement = secret.secret_value_new(payload, NativeLong(-1), "text/plain")
                    value = replacement
                    secret.secret_item_set_secret_sync(readable.getValue(record), replacement, cancellable, error)
                } else {
                    secret.secret_password_storev_sync(null, table, null, "Metrolist Desktop Secrets", payload, cancellable, error)
                }
            CredentialCommandResult(if (saved != 0) 0 else 1, "", "")
        } finally {
            value?.let(secret::secret_value_unref)
            itemPointers.forEach(gobject::g_object_unref)
            items?.let(glib::g_list_free)
            error.value?.let(glib::g_error_free)
            synchronized(cancellationLock) {
                finished = true
                timeout.cancel(false)
                gobject.g_object_unref(cancellable)
            }
            glib.g_hash_table_unref(table)
        }
    }
}
