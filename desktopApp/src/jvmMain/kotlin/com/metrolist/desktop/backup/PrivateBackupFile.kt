package com.metrolist.desktop.backup

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.backup_create_failed
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet

internal fun createPrivateBackupFile(
    directory: Path,
    prefix: String,
): Path {
    val store = Files.getFileStore(directory)
    val permissions: FileAttribute<*> =
        when {
            store.supportsFileAttributeView("posix") -> {
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            }

            store.supportsFileAttributeView("acl") -> {
                val owner = directory.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
                val acl =
                    listOf(
                        AclEntry
                            .newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(owner)
                            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                            .build(),
                    )
                object : FileAttribute<List<AclEntry>> {
                    override fun name() = "acl:acl"

                    override fun value() = acl
                }
            }

            else -> {
                error(runBlocking { getString(Res.string.backup_create_failed) })
            }
        }
    return Files.createTempFile(directory, prefix, ".tmp", permissions)
}
