package com.example.blocktel1

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Создание, обновление и удаление контактов через ContactsContract.
 */
object ContactRepository {

    private const val TAG = "ContactRepo"

    suspend fun loadStructuredName(
    context: Context,
    contactId: String
): Triple<String, String, String> = withContext(Dispatchers.IO) {
    var firstName = ""
    var lastName = ""
    var middleName = ""
    try {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME
            ),
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                firstName = c.getString(0) ?: ""
                lastName = c.getString(1) ?: ""
                middleName = c.getString(2) ?: ""
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "loadStructuredName: ${e.message}")
    }
    Triple(firstName, lastName, middleName)
}
    private fun pickWritableAccount(context: Context): Pair<String?, String?> {
        return try {
            val am = android.accounts.AccountManager.get(context)

            val googleAccounts = am.getAccountsByType("com.google")
            if (googleAccounts.isNotEmpty()) {
                return "com.google" to googleAccounts.first().name
            }

            val allAccounts = am.accounts
            for (acc in allAccounts) {
                val isContactsWritable = try {
                    context.contentResolver.query(
                        ContactsContract.RawContacts.CONTENT_URI,
                        arrayOf(
                            ContactsContract.RawContacts.ACCOUNT_TYPE,
                            ContactsContract.RawContacts.ACCOUNT_NAME
                        ),
                        "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=?",
                        arrayOf(acc.type, acc.name),
                        null
                    )?.use { it.moveToFirst() } ?: false
                } catch (e: Exception) {
                    false
                }
                if (isContactsWritable) {
                    return acc.type to acc.name
                }
            }

            null to null
        } catch (e: Exception) {
            Log.e(TAG, "pickWritableAccount: ${e.message}")
            null to null
        }
    }
    /** Вставка нового контакта. */
    /** Вставка нового контакта. */
suspend fun insertContact(context: Context, draft: ContactDraft): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val (accType, accName) = pickWritableAccount(context)
            if (accType == null) {
                Log.e(TAG, "insertContact: нет доступного аккаунта для записи контактов")
                return@withContext false
            }

            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accType)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accName)
                    .build()
            )
            addNameAndPhones(ops, draft)

            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawContactId = results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()

            if (!draft.photoUri.isNullOrBlank() && rawContactId != null) {
                // Компактное круглое фото 512×512 с учётом EXIF
                val bytes = ContactPhotoStore.loadCroppedCirclePhoto(context, draft.photoUri, 512)
                if (bytes != null) {
                    Log.d(TAG, "insertContact: circle photo size = ${bytes.size} bytes")

                    // Получаем CONTACT_ID для этого rawContactId,
                    // чтобы сохранить фото под тем же ключом, что использует loadContacts
                    var contactId: String? = null
                    context.contentResolver.query(
                        ContactsContract.RawContacts.CONTENT_URI,
                        arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                        "${ContactsContract.RawContacts._ID}=?",
                        arrayOf(rawContactId.toString()),
                        null
                    )?.use { c ->
                        if (c.moveToFirst()) contactId = c.getString(0)
                    }

                    if (contactId != null) {
                        // 1. Локально — по contactId, чтобы loadContacts нашёл
                        ContactPhotoStore.savePhoto(context, contactId!!, bytes)
                        // 2. В Google через DisplayPhoto — попытка синхронизации
                        writeDisplayPhoto(context, rawContactId, bytes)
                    } else {
                        Log.e(TAG, "insertContact: не удалось получить CONTACT_ID для rawContactId=$rawContactId")
                    }
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "insertContact: ${e.message}")
            false
        }
    }

/** Обновление существующего контакта. */
suspend fun updateContact(context: Context, draft: ContactDraft): Boolean =
    withContext(Dispatchers.IO) {
        val id = draft.id ?: return@withContext insertContact(context, draft)
        try {
            // 1. Находим ВСЕ rawContactId для этого CONTACT_ID
            val rawContactIds = mutableListOf<Long>()
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID}=?",
                arrayOf(id),
                null
            )?.use { c ->
                while (c.moveToNext()) rawContactIds.add(c.getLong(0))
            }

            if (rawContactIds.isEmpty()) {
                return@withContext insertContact(context, draft)
            }

            val primaryRawId = rawContactIds.first()
            val ops = ArrayList<ContentProviderOperation>()

            // 2. Обновляем имя у первого raw-контакта
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                        arrayOf(primaryRawId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, draft.firstName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, draft.lastName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, draft.middleName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, draft.displayName)
                    .build()
            )

            // 3. Удаляем телефоны у ВСЕХ raw-контактов
            rawContactIds.forEach { rawId ->
                ops.add(
                    ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection(
                            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                            arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        )
                        .build()
                )
            }

            // 4. Вставляем актуальные телефоны в первый raw-контакт (без дубликатов)
            draft.phones
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { phone ->
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, primaryRawId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                            .build()
                    )
                }

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)

            // 5. Фото — по contactId
            if (!draft.photoUri.isNullOrBlank()) {
                val bytes = ContactPhotoStore.loadCroppedCirclePhoto(context, draft.photoUri, 512)
                if (bytes != null) {
                    Log.d(TAG, "updateContact: circle photo size = ${bytes.size} bytes")
                    ContactPhotoStore.savePhoto(context, id, bytes)
                    writeDisplayPhoto(context, primaryRawId, bytes)
                }
            } else {
                // Фото удалили в форме — удаляем локально
                ContactPhotoStore.deletePhoto(context, id)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "updateContact: ${e.message}")
            false
        }
    }


    private fun writeDisplayPhotoToRawContact(
        context: Context,
        rawContactId: Long,
        photoBytes: ByteArray
    ) {
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
            Log.d(TAG, "writeDisplayPhoto: ${photoBytes.size} bytes -> rawContactId=$rawContactId")
        } catch (e: Exception) {
            Log.e(TAG, "writeDisplayPhoto: ${e.message}")
        }
    }

    /** Обновление существующего контакта (удаляем raw-контакты и вставляем заново). */


    /** Удаление контакта по contactId. */
    suspend fun deleteContact(context: Context, contactId: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val ok = context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID}=?",
                arrayOf(contactId)
            ) > 0
            if (ok) ContactPhotoStore.deletePhoto(context, contactId)
            ok
        } catch (e: Exception) {
            Log.e(TAG, "deleteContact: ${e.message}")
            false
        }
    }

    /** Импорт VcfContact в системную книгу. */
    suspend fun importVcfContact(context: Context, vcf: VcfContact): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val ops = ArrayList<ContentProviderOperation>()
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(
                            ContactsContract.Data.RAW_CONTACT_ID, 0
                        )
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                        )
                        .withValue(
                            ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                            vcf.firstName
                        )
                        .withValue(
                            ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                            vcf.lastName
                        )
                        .withValue(
                            ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                            vcf.middleName
                        )
                        .withValue(
                            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                            vcf.displayName
                        )
                        .build()
                )
                vcf.phones.forEach { phone ->
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(
                                ContactsContract.Data.RAW_CONTACT_ID, 0
                            )
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                            )
                            .withValue(
                                ContactsContract.CommonDataKinds.Phone.NUMBER,
                                phone
                            )
                            .withValue(
                                ContactsContract.CommonDataKinds.Phone.TYPE,
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                            )
                            .build()
                    )
                }
                if (vcf.photoBytes != null) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(
                                ContactsContract.Data.RAW_CONTACT_ID, 0
                            )
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
                            )
                            .withValue(
                                ContactsContract.CommonDataKinds.Photo.PHOTO,
                                vcf.photoBytes
                            )
                            .build()
                    )
                }
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                true
            } catch (e: Exception) {
                Log.e(TAG, "importVcfContact: ${e.message}")
                false
            }
        }

    private fun addNameAndPhones(
        ops: ArrayList<ContentProviderOperation>,
        draft: ContactDraft
    ) {
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
                .withValue(
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                    draft.firstName
                )
                .withValue(
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                    draft.lastName
                )
                .withValue(
                    ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                    draft.middleName
                )
                .withValue(
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    draft.displayName
                )
                .build()
        )

        draft.phones
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { phone ->
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(
                            ContactsContract.Data.RAW_CONTACT_ID, 0
                        )
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                        )
                        .withValue(
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            phone
                        )
                        .withValue(
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        )
                        .build()
                )
            }
    }

    private fun addPhoto(
        ops: ArrayList<ContentProviderOperation>,
        context: Context,
        draft: ContactDraft
    ) {
        val uriStr = draft.photoUri ?: return
        val bytes = readPhotoBytes(context, uriStr) ?: return
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
                )
                .withValue(
                    ContactsContract.CommonDataKinds.Photo.PHOTO,
                    bytes
                )
                .build()
        )
    }
}