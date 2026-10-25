package com.example.blocktel1

import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Кодирование/декодирование quoted-printable (используется в VCF 2.1).
 */
object QP {

    fun decode(input: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '=') {
                if (i + 1 < input.length && input[i + 1] == '\n') { i += 2; continue }
                if (i + 1 < input.length && input[i + 1] == '\r') { i += 2; continue }
                if (i + 2 < input.length) {
                    val hex = input.substring(i + 1, i + 3)
                    val v = hex.toIntOrNull(16)
                    if (v != null) {
                        out.write(v)
                        i += 3
                        continue
                    }
                }
            }
            out.write(c.code and 0xFF)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun encode(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var lineLen = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val safe = (v in 33..126) && v != 61
            if (safe) {
                sb.append(v.toChar())
                lineLen++
            } else {
                sb.append('=').append("%02X".format(v))
                lineLen += 3
            }
            if (lineLen >= 72) {
                sb.append("=\r\n")
                lineLen = 0
            }
        }
        return sb.toString()
    }
}

/**
 * Разбирает VCF-файл (поддерживает VERSION:2.1 с QUOTED-PRINTABLE и PHOTO).
 */
fun parseVcf(text: String): List<VcfContact> {
    val result = mutableListOf<VcfContact>()
    val cards = text.split(Regex("(?m)^BEGIN:VCARD\\s*$")).drop(1)

    for (card in cards) {
        val body = card.substringBefore("END:VCARD")

        val unfolded = body
            .replace(Regex("=\\r?\\n"), "")
            .replace(Regex("\\r?\\n[ \\t]"), "")

        var firstName = ""
        var lastName = ""
        var middleName = ""
        var fn = ""
        val phones = mutableListOf<String>()
        var photo: ByteArray? = null

        unfolded.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("N;", true) || line.startsWith("N:", true) -> {
                    val value = line.substringAfter(':')
                    val parts = splitUnescaped(value, ';')
                    lastName = QP.decode(parts.getOrElse(0) { "" })
                    firstName = QP.decode(parts.getOrElse(1) { "" })
                    middleName = QP.decode(parts.getOrElse(2) { "" })
                }

                line.startsWith("FN", true) -> {
                    fn = QP.decode(line.substringAfter(':'))
                }

                line.startsWith("TEL", true) -> {
                    val v = line.substringAfter(':').trim()
                    if (v.isNotBlank()) phones.add(v)
                }

                line.startsWith("PHOTO", true) -> {
                    val b64 = line.substringAfter(':').replace(Regex("\\s"), "")
                    photo = try {
                        Base64.decode(b64, Base64.DEFAULT)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }

        val finalDisplay = fn.ifBlank {
            listOf(lastName, firstName, middleName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }

        result.add(
            VcfContact(
                firstName = firstName,
                lastName = lastName,
                middleName = middleName,
                displayName = finalDisplay,
                phones = phones,
                photoBytes = photo
            )
        )
    }
    return result
}

/**
 * Формирует VCF-текст из списка контактов.
 * @param contacts список контактов
 * @param photoProvider возвращает байты фото для конкретного photoUri (или null)
 */
fun exportContactsToVcf(
    contacts: List<Contact>,
    photoProvider: (String) -> ByteArray?
): String {
    val sb = StringBuilder()

    contacts.forEach { c ->
        sb.append("BEGIN:VCARD\r\n")
        sb.append("VERSION:2.1\r\n")

        val nameParts = c.name.trim().split(Regex("\\s+"), limit = 2)
        val family = nameParts.getOrElse(0) { "" }
        val given = nameParts.getOrElse(1) { "" }

        sb.append("N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:")
        sb.append(QP.encode(family)).append(";")
        sb.append(QP.encode(given)).append(";;;\r\n")

        sb.append("FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:")
        sb.append(QP.encode(c.name)).append("\r\n")

        if (c.phoneNumber.isNotBlank()) {
            val clean = c.phoneNumber.filter { it.isDigit() || it == '+' }
            sb.append("TEL;CELL:").append(clean).append("\r\n")
        }

        val photoBytes = c.photoUri?.let { photoProvider(it) }
        if (photoBytes != null) {
            sb.append("PHOTO;ENCODING=BASE64;JPEG:")
            sb.append(Base64.encodeToString(photoBytes, Base64.NO_WRAP))
            sb.append("\r\n")
        }

        sb.append("END:VCARD\r\n")
    }

    return sb.toString()
}

private fun splitUnescaped(s: String, sep: Char): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var escaped = false
    for (c in s) {
        when {
            escaped -> { sb.append(c); escaped = false }
            c == '\\' -> escaped = true
            c == sep -> { out.add(sb.toString()); sb.clear() }
            else -> sb.append(c)
        }
    }
    out.add(sb.toString())
    return out
}