package com.example.blocktel1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Локальное хранилище фото контактов.
 * Фото лежат в filesDir/contact_photos/<contactId>.png
 * Привязка contactId -> путь хранится в SharedPreferences (на случай переезда файла).
 */
object ContactPhotoStore {

    private const val TAG = "ContactPhotoStore"
    private const val PREFS = "contact_photos_prefs"
    private const val KEY_PREFIX = "photo_"
    private const val ORIENT_PREFIX = "orientation_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun photoDir(context: Context): File =
        File(context.filesDir, "contact_photos").apply { mkdirs() }

    private fun photoFile(context: Context, contactId: String): File =
        File(photoDir(context), "$contactId.png")

    // ---------------------------------------------------------------
    // EXIF
    // ---------------------------------------------------------------

    /**
     * Читает EXIF-ориентацию из файла по URI и поворачивает битмап соответственно.
     */
    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(input)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            input.close()
            Log.d(TAG, "applyExifOrientation: orientation=$orientation, file=${uri.lastPathSegment}")
            applyMatrix(bitmap, orientation)
        } catch (e: Exception) {
            Log.e(TAG, "applyExifOrientation: ${e.message}")
            bitmap
        }
    }

    /**
     * Применяет EXIF-ориентацию, сохранённую в SharedPreferences при copyToCache.
     * Нужна, потому что при копировании файла в кэш EXIF может быть потерян.
     */
    private fun applyExifOrientationFromPrefs(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val fileName = uri.lastPathSegment ?: return bitmap
            val orientation = prefs(context).getInt(
                ORIENT_PREFIX + fileName,
                ExifInterface.ORIENTATION_NORMAL
            )
            Log.d(TAG, "applyExifOrientationFromPrefs: orientation=$orientation, file=$fileName")
            applyMatrix(bitmap, orientation)
        } catch (e: Exception) {
            Log.e(TAG, "applyExifOrientationFromPrefs: ${e.message}")
            bitmap
        }
    }

    /**
     * Поворачивает/отражает битмап по коду ориентации.
     */
    private fun applyMatrix(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ---------------------------------------------------------------
    // Файлы
    // ---------------------------------------------------------------

    /** Сохраняет байты фото для контакта. Возвращает true при успехе. */
    fun savePhoto(context: Context, contactId: String, bytes: ByteArray): Boolean {
        return try {
            val file = photoFile(context, contactId)
            file.outputStream().use { it.write(bytes) }
            prefs(context).edit().putString(KEY_PREFIX + contactId, file.absolutePath).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "savePhoto: ${e.message}")
            false
        }
    }

    /** Возвращает путь к файлу фото контакта или null, если фото нет. */
    fun getPhotoPath(context: Context, contactId: String): String? {
        val path = prefs(context).getString(KEY_PREFIX + contactId, null)
        if (path != null && File(path).exists()) return path
        val file = photoFile(context, contactId)
        return if (file.exists()) file.absolutePath else null
    }

    /** Возвращает FileProvider-URI для фото контакта (для AsyncImage/Coil). */

fun getPhotoUri(context: Context, contactId: String): Uri? {
    val path = getPhotoPath(context, contactId) ?: return null
    return try {
        val file = File(path)
        val base = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        // Добавляем версию по времени модификации файла
        base.buildUpon()
            .appendQueryParameter("v", file.lastModified().toString())
            .build()
    } catch (e: Exception) {
        Log.e(TAG, "getPhotoUri: ${e.message}")
        null
    }
}
    /** Удаляет фото контакта (при удалении контакта). */
    fun deletePhoto(context: Context, contactId: String) {
        try {
            photoFile(context, contactId).delete()
            prefs(context).edit().remove(KEY_PREFIX + contactId).apply()
        } catch (e: Exception) {
            Log.e(TAG, "deletePhoto: ${e.message}")
        }
    }

    /** Копирует выбранное пользователем фото во внутренний кэш и возвращает URI. */
    fun copyToCache(context: Context, uri: Uri): Uri? {
        return try {
            val dir = File(context.cacheDir, "contact_photos").apply { mkdirs() }
            val out = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }

            // Сохраняем EXIF-ориентацию из оригинала в SharedPreferences
            try {
                context.contentResolver.openInputStream(uri)?.use { exifInput ->
                    val exif = ExifInterface(exifInput)
                    val orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                    prefs(context).edit()
                        .putInt(ORIENT_PREFIX + out.name, orientation)
                        .apply()
                    Log.d(TAG, "copyToCache: saved orientation=$orientation for ${out.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "copyToCache exif: ${e.message}")
            }

            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
        } catch (e: Exception) {
            Log.e(TAG, "copyToCache: ${e.message}")
            null
        }
    }

    /** Читает байты фото по URI (для экспорта в VCF). */
    fun readBytes(context: Context, uriString: String): ByteArray? {
        return try {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "readBytes: ${e.message}")
            null
        }
    }



    /**
     * Читает фото по URI, поворачивает по EXIF (из файла или из SharedPreferences),
     * обрезает по центру в квадрат, уменьшает до size×size,
     * делает круглую маску (прозрачные углы), сжимает в PNG.
     * Возвращает компактные байты (обычно 30–100 КБ для size=512).
     */
    fun loadCroppedCirclePhoto(
        context: Context,
        uriString: String,
        size: Int = 512
    ): ByteArray? {
        return try {
            val uri = Uri.parse(uriString)
            val input = context.contentResolver.openInputStream(uri) ?: return null
            var original = BitmapFactory.decodeStream(input)
            input.close()
            if (original == null) return null

            Log.d(TAG, "before exif: ${original.width}x${original.height}")

            // ПРИМЕНЯЕМ ОРИЕНТАЦИЮ ТОЛЬКО ОДИН РАЗ — из prefs (сохранена при copyToCache)
            original = applyExifOrientationFromPrefs(context, uri, original)

            Log.d(TAG, "after exif: ${original.width}x${original.height}")

            // 1. Квадратная обрезка по центру
            val side = minOf(original.width, original.height)
            val left = (original.width - side) / 2
            val top = (original.height - side) / 2
            val square = Bitmap.createBitmap(original, left, top, side, side)

            // 2. Уменьшение до size×size
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)

            // 3. Круглая маска
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawARGB(0, 0, 0, 0)
            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(scaled, 0f, 0f, paint)

            // 4. PNG (сохраняет прозрачность)
            val baos = ByteArrayOutputStream()
            output.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()

            Log.d(TAG, "loadCroppedCirclePhoto: ${bytes.size} bytes, ${size}x${size}")
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "loadCroppedCirclePhoto: ${e.message}")
            null
        }
    }
}