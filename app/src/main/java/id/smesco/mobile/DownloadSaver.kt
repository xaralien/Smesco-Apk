package id.smesco.mobile

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Menyimpan berkas yang dibuat di dalam halaman (blob:/data:) ke folder Download. */
object DownloadSaver {

    class Saved(val uri: Uri, val displayName: String, val mimeType: String)

    /** Memecah data URL `data:<mime>;base64,<isi>` menjadi (mime, bytes). */
    fun decodeDataUrl(dataUrl: String): Pair<String, ByteArray> {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) throw IOException("Data URL tidak valid")
        val meta = dataUrl.substring(5, comma)
        val payload = dataUrl.substring(comma + 1)
        val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
        val bytes = if (meta.contains(";base64", ignoreCase = true)) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }
        return mime to bytes
    }

    fun save(context: Context, fileName: String, mimeType: String, bytes: ByteArray): Saved {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Tidak bisa membuat berkas di Download")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IOException("Tidak bisa menulis berkas")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            Saved(uri, fileName, mimeType)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = uniqueFile(dir, fileName)
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Saved(uri, file.name, mimeType)
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    fun openIntent(saved: Saved): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(saved.uri, saved.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
