package com.example.blocktel1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Черновик контакта для формы редактирования/создания.
 * id == null — это новый контакт.
 */
data class ContactDraft(
    val id: String? = null,
    val firstName: String = "",
    val lastName: String = "",
    val middleName: String = "",
    val displayName: String = "",
    val phones: List<String> = listOf(""),
    val photoUri: String? = null
)

/**
 * Контакт, разобранный из VCF-файла (для импорта).
 */
data class VcfContact(
    val firstName: String,
    val lastName: String,
    val middleName: String,
    val displayName: String,
    val phones: List<String>,
    val photoBytes: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VcfContact) return false
        return firstName == other.firstName &&
                lastName == other.lastName &&
                middleName == other.middleName &&
                displayName == other.displayName &&
                phones == other.phones &&
                photoBytes.contentEquals(other.photoBytes)
    }

    override fun hashCode(): Int {
        var result = firstName.hashCode()
        result = 31 * result + lastName.hashCode()
        result = 31 * result + middleName.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + phones.hashCode()
        result = 31 * result + (photoBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Копирует выбранное пользователем фото (content:// URI) во внутренний кэш
 * и возвращает FileProvider-URI, который можно сохранить в контакт.
 */
fun copyPhotoToCache(context: Context, uri: Uri): Uri? {
    return try {
        val dir = File(context.cacheDir, "contact_photos").apply { mkdirs() }
        val out = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
    } catch (e: Exception) {
        Log.e("ContactPhoto", "copyPhotoToCache: ${e.message}")
        null
    }
}

/**
 * Читает байты фото по URI (для экспорта в VCF).
 */
fun readPhotoBytes(context: Context, uriString: String): ByteArray? {
    return try {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
    } catch (e: Exception) {
        Log.e("ContactPhoto", "readPhotoBytes: ${e.message}")
        null
    }
}

/**
 * Читает фото по URI, НЕ меняет разрешение, делает углы прозрачными (круг).
 * Возвращает PNG-байты с прозрачными углами.
 */
/**
 * Читает фото по URI, обрезает по центру в квадрат, уменьшает до size×size,
 * делает круглую маску (прозрачные углы), сжимает в PNG.
 * Возвращает компактные байты (обычно 30–100 КБ для size=512).
 */


/**
 * Записывает байты фото в raw-контакт через DisplayPhoto
 * (обходит Binder-лимит 1 МБ, разрешение не меняется).
 */
fun writeDisplayPhoto(context: Context, rawContactId: Long, photoBytes: ByteArray) {
    try {
        val rawContactUri = android.content.ContentUris.withAppendedId(
            ContactsContract.RawContacts.CONTENT_URI,
            rawContactId
        )
        val photoUri = Uri.withAppendedPath(
            rawContactUri,
            ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY
        )
        context.contentResolver.openAssetFileDescriptor(photoUri, "rw")?.use { fd ->
            fd.createOutputStream().use { os -> os.write(photoBytes) }
        }
    } catch (e: Exception) {
        Log.e("ContactPhoto", "writeDisplayPhoto: ${e.message}")
    }
}