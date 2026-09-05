package dev.rusty.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Writes a live-view frame to Pictures/Rusty. Pure naming is separate so it can be unit-tested. */
object CameraSnapshotSaver {
    private const val FOLDER = "Rusty"

    fun fileName(cameraName: String, timeMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val slug = cameraName.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "camera" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = zone }.format(Date(timeMillis))
        return "${slug}_$stamp.jpg"
    }

    suspend fun save(context: Context, bitmap: Bitmap, cameraName: String, timeMillis: Long): Boolean =
        withContext(Dispatchers.IO) {
            val name = fileName(cameraName, timeMillis)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + FOLDER)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                    val ok = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } ?: false
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    if (!ok) resolver.delete(uri, null, null)
                    ok
                } else {
                    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), FOLDER)
                    if (!dir.isDirectory && !dir.mkdirs()) return@withContext false
                    File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                }
            } catch (_: Exception) {
                false
            }
        }
}
