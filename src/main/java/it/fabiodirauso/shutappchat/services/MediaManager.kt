package it.fabiodirauso.shutappchat.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import it.fabiodirauso.shutappchat.utils.FileUtils
import java.io.ByteArrayOutputStream
import java.io.File

class MediaManager(private val context: Context) {

    fun generateThumbnail(file: File): String? {
        return try {
            val bitmap = BitmapFactory.decodeFile(file.path)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
            val thumbnailBytes = stream.toByteArray()
            android.util.Base64.encodeToString(thumbnailBytes, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    fun isImageMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }

    fun getFileFromUri(uri: Uri): File? {
        return FileUtils.getFileFromUri(context, uri)
    }

    fun getMimeType(uri: Uri): String? {
        return FileUtils.getMimeType(context, uri)
    }

    fun getFileSize(file: File): Long {
        return FileUtils.getFileSize(file)
    }
}
