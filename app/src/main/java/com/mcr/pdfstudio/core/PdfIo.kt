package com.mcr.pdfstudio.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Everything that crosses the Storage Access Framework boundary.
 *
 * PdfBox wants random access to a real file, while SAF hands us an opaque
 * content stream, so documents are staged in cache and written back on save.
 */
object PdfIo {

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.lastPathSegment ?: "document.pdf"
        }
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount > 0) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    /** Stages [uri] into app cache and returns the local file. */
    fun stage(context: Context, uri: Uri, fileName: String = "staged.pdf"): File {
        val target = workFile(context, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: error("Cannot open $uri")
        return target
    }

    fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot open $uri")

    /** Overwrites the document behind [uri]. Used by plain "Save". */
    fun writeBack(context: Context, uri: Uri, source: File) {
        // "wt" truncates first; without it a shorter document leaves trailing
        // bytes from the previous revision and the PDF becomes unreadable.
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Cannot write to $uri")
    }

    fun writeBack(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("Cannot write to $uri")
    }

    fun workDir(context: Context): File =
        File(context.cacheDir, "work").apply { mkdirs() }

    fun workFile(context: Context, name: String): File =
        File(workDir(context), sanitize(name))

    fun exportDir(context: Context): File =
        File(context.cacheDir, "export").apply { mkdirs() }

    fun exportFile(context: Context, name: String): File =
        File(exportDir(context), sanitize(name))

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._\\-() ]"), "_").trim()
        // Illegal characters become underscores, so a name made entirely of
        // them is never empty — it is just useless. Treat that as no name.
        val meaningless = cleaned.all { it == '_' || it == '.' || it == ' ' || it == '-' }
        return if (cleaned.isEmpty() || meaningless) "document" else cleaned.take(120)
    }

    fun baseName(name: String): String =
        name.substringBeforeLast('.', name).ifBlank { "document" }

    fun clearWork(context: Context) {
        workDir(context).listFiles()?.forEach { it.delete() }
    }
}
