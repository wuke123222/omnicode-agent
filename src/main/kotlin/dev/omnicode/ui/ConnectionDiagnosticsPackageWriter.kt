package dev.omnicode.ui

import dev.omnicode.service.ConnectionDiagnosticsExport
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ConnectionDiagnosticsWriteException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Writes a bounded, owner-only, create-new ZIP to the path explicitly selected by the user. */
internal class ConnectionDiagnosticsPackageWriter {
    fun write(export: ConnectionDiagnosticsExport, selectedPath: Path): Path {
        val destination = selectedPath.toAbsolutePath().normalize()
        val parent = destination.parent
            ?: throw ConnectionDiagnosticsWriteException("诊断包路径必须包含父目录。")
        if (destination.fileName.toString().lowercase().endsWith(".zip").not()) {
            throw ConnectionDiagnosticsWriteException("诊断包必须使用 .zip 扩展名。")
        }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw ConnectionDiagnosticsWriteException("目标父目录必须是可信的真实目录。")
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw ConnectionDiagnosticsWriteException("目标文件已存在；请选择新文件名，现有文件未被覆盖。")
        }
        val markdown = export.markdown.toByteArray(StandardCharsets.UTF_8)
        val json = export.json.toByteArray(StandardCharsets.UTF_8)
        if (markdown.size + json.size > MAX_DIAGNOSTICS_EXPORT_BYTES) {
            throw ConnectionDiagnosticsWriteException("脱敏诊断包超过 1 MB 上限。")
        }
        val temporary = try {
            Files.createTempFile(parent, ".${destination.fileName}.", ".tmp")
        } catch (error: IOException) {
            throw ConnectionDiagnosticsWriteException("无法创建诊断包临时文件。", error)
        }
        try {
            restrictOwner(temporary)
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val zip = ZipOutputStream(java.nio.channels.Channels.newOutputStream(channel), StandardCharsets.UTF_8)
                try {
                    writeEntry(zip, "diagnostics.md", markdown)
                    writeEntry(zip, "diagnostics.json", json)
                    zip.finish()
                    zip.flush()
                    channel.force(true)
                } finally {
                    zip.close()
                }
            }
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw ConnectionDiagnosticsWriteException("目标文件在写入前出现；未覆盖任何文件。")
            }
            try {
                Files.createLink(destination, temporary)
            } catch (error: FileAlreadyExistsException) {
                throw ConnectionDiagnosticsWriteException("目标文件在提交时出现；未覆盖任何文件。", error)
            } catch (error: IOException) {
                throw ConnectionDiagnosticsWriteException("当前文件系统无法安全地创建诊断包。", error)
            }
            restrictOwner(destination)
            return destination
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply { time = 0L }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun restrictOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private companion object {
        const val MAX_DIAGNOSTICS_EXPORT_BYTES = 1_048_576
    }
}
