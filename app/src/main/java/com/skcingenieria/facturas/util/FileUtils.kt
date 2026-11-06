package com.skcingenieria.facturas.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.skcingenieria.facturas.data.Attachment
import com.skcingenieria.facturas.data.STORAGE_PENDING
import com.skcingenieria.facturas.data.StorageConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object FileUtils {
    private const val HARD_MAX_BYTES = 50L * 1024L * 1024L
    private val imageMime = setOf("image/jpeg", "image/png", "image/webp")
    private val knownMime = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "application/pdf")

    private fun safeName(name: String): String = name.replace("[^A-Za-z0-9._-]".toRegex(), "_").take(100).ifBlank { "evidencia" }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "evidencia"
        }
        return uri.lastPathSegment ?: "evidencia"
    }

    private fun mimeFor(context: Context, uri: Uri, name: String): String {
        context.contentResolver.getType(uri)?.let { return it.lowercase(Locale.ROOT) }
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
            "gif" -> "image/gif"; "heic" -> "image/heic"; "heif" -> "image/heif"; "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun maxBytes(mime: String, storage: StorageConfig): Long = when {
        mime.startsWith("image/") -> storage.imageMaxMb.toLong() * 1024L * 1024L
        mime == "application/pdf" -> storage.pdfMaxMb.toLong() * 1024L * 1024L
        else -> storage.otherMaxMb.toLong() * 1024L * 1024L
    }

    fun stageUri(context: Context, entityId: String, uri: Uri, storage: StorageConfig = StorageConfig()): Attachment {
        val original = displayName(context, uri)
        val mime = mimeFor(context, uri, original)
        val ext = extension(original)
        require(ext.isNotBlank() && ext in storage.allowedExtensions.map { it.lowercase().trimStart('.') }.toSet()) { "Extensión no permitida: .$ext" }
        require(mime in knownMime || mime == "application/octet-stream") { "Formato no permitido: $mime" }

        val dir = File(context.filesDir, "evidence/$entityId").apply { mkdirs() }
        val raw = File(dir, "raw_${CodeUtils.uuid().take(8)}_${safeName(original)}")
        val target = File(dir, "${CodeUtils.uuid().take(8)}_${safeName(original)}")
        try {
            var rawSize = 0L
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "No se pudo abrir la evidencia" }
                FileOutputStream(raw).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        rawSize += n
                        require(rawSize <= HARD_MAX_BYTES) { "La evidencia supera 50 MB" }
                        output.write(buffer, 0, n)
                    }
                }
            }
            require(rawSize > 0) { "La evidencia está vacía" }

            val optimized = if (storage.optimizeImages && mime in imageMime) optimizeImage(raw, target, mime, storage) else false
            if (!optimized) raw.copyTo(target, overwrite = true)

            val finalSize = target.length()
            require(finalSize > 0) { "La evidencia está vacía" }
            require(finalSize <= maxBytes(mime, storage)) {
                "El archivo supera el límite configurado de ${maxBytes(mime, storage) / (1024 * 1024)} MB"
            }
            val sha = sha256(target)
            raw.delete()
            return Attachment(
                id = CodeUtils.uuid(),
                name = original,
                mime = mime,
                size = finalSize,
                sha256 = sha,
                localPath = target.absolutePath,
                storageStatus = STORAGE_PENDING
            )
        } catch (e: Throwable) {
            // A failed copy/optimization must not leave orphan evidence files behind.
            raw.delete()
            target.delete()
            throw e
        }
    }

    private fun optimizeImage(source: File, target: File, mime: String, storage: StorageConfig): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (bounds.outWidth / sample > storage.imageMaxDimension * 2 || bounds.outHeight / sample > storage.imageMaxDimension * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val bitmap = if (mime == "image/jpeg") applyExifOrientation(source, decoded) else decoded
        if (bitmap !== decoded) decoded.recycle()
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxSide > storage.imageMaxDimension) {
            val ratio = storage.imageMaxDimension.toDouble() / maxSide.toDouble()
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        } else bitmap

        val format = when (mime) {
            "image/jpeg" -> Bitmap.CompressFormat.JPEG
            "image/png" -> Bitmap.CompressFormat.PNG
            "image/webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            else -> return false
        }
        FileOutputStream(target).use { out ->
            scaled.compress(format, storage.imageQuality, out)
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        if (target.length() <= 0 || target.length() >= source.length()) {
            target.delete()
            return false
        }
        return true
    }


    private fun applyExifOrientation(source: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(90f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n <= 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun combinedHash(items: List<Attachment>): String = if (items.isEmpty()) "" else CodeUtils.sha256Text(items.map { it.sha256 }.sorted().joinToString("|"))

    fun evidenceStorageKey(basePrefix: String, authUserId: String, kind: String, date: String, code: String, index: Int, attachment: Attachment): String {
        val parts = date.split("-")
        val y = parts.getOrElse(0) { "0000" }; val m = parts.getOrElse(1) { "00" }; val d = parts.getOrElse(2) { "00" }
        val ext = attachment.name.substringAfterLast('.', "").lowercase(Locale.ROOT).ifBlank {
            when (attachment.mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "application/pdf" -> "pdf"; else -> "bin" }
        }
        val prefix = basePrefix.trim('/').ifBlank { "skc" }
        val owner = authUserId.ifBlank { "unbound" }
        return "$prefix/$owner/$kind/$y/$m/$d/$code/${attachment.id.take(12)}_${(index + 1).toString().padStart(2, '0')}.$ext"
    }

    fun evidenceRemotePath(kind: String, date: String, code: String, index: Int, attachment: Attachment): String =
        evidenceStorageKey("skc", "legacy", kind, date, code, index, attachment)

    fun zipEvidence(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(safeName(name)))
                zip.write(bytes); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
