package com.example.teleprompter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.zip.ZipInputStream

/**
 * Импорт скрипта из файла — ПОЛНОСТЬЮ ОФЛАЙН (ничего не отправляется наружу).
 * Поддержка:
 *   • .txt / .md / любой текстовый файл — как обычный текст (UTF-8);
 *   • .docx (MS Word) — распаковываем zip и достаём текст из word/document.xml
 *     БЕЗ сторонних библиотек (java.util.zip + разбор тегов w:p/w:t/w:tab/w:br).
 * Старый .doc (бинарный, до 2007) НЕ поддерживается — просим сохранить как .docx или .txt.
 */
object FileImport {

    /** MIME-типы для системного выбора файла (ACTION_OPEN_DOCUMENT). */
    val MIME_TYPES = arrayOf(
        "text/plain",
        "text/markdown",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // .docx
        "application/msword",   // .doc (перехватим и подскажем сохранить как .docx)
        "text/*"
    )

    data class Imported(val title: String, val body: String)

    /** Результат импорта: либо текст, либо человекочитаемая ошибка. */
    sealed class Result {
        data class Ok(val value: Imported) : Result()
        data class Error(val messageRes: Int) : Result()
    }

    fun read(context: Context, uri: Uri): Result {
        val name = displayName(context, uri)
        val lower = name.lowercase()
        return try {
            when {
                lower.endsWith(".docx") -> {
                    val body = readDocx(context, uri)
                    if (body.isBlank()) Result.Error(R.string.import_error_empty)
                    else Result.Ok(Imported(titleFrom(name), body))
                }
                lower.endsWith(".doc") -> Result.Error(R.string.import_error_doc_old)
                else -> {
                    // txt / md / прочий текст.
                    val body = readText(context, uri)
                    if (body.isBlank()) Result.Error(R.string.import_error_empty)
                    else Result.Ok(Imported(titleFrom(name), body))
                }
            }
        } catch (_: Exception) {
            Result.Error(R.string.import_error_read)
        }
    }

    // --- Чтение обычного текста -----------------------------------------------

    private fun readText(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri).use { input ->
            input ?: return ""
            return input.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n")
        }
    }

    // --- Чтение .docx ---------------------------------------------------------

    private fun readDocx(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri).use { input ->
            input ?: return ""
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        return docxXmlToText(xml)
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return ""
    }

    /** Достаём читаемый текст из word/document.xml: параграфы → переводы строк, w:t → текст. */
    private fun docxXmlToText(xml: String): String {
        var s = xml
        // Разрывы строк и абзацы прошивки Word.
        s = s.replace(Regex("<w:br\\s*/?>"), "\n")
        s = s.replace(Regex("<w:tab\\s*/?>"), "\t")
        s = s.replace("</w:p>", "\n")     // конец абзаца
        // Убираем ВСЕ теги, оставляя только текстовое содержимое (в т.ч. внутри <w:t>).
        s = s.replace(Regex("<[^>]+>"), "")
        // Раскодируем XML-сущности.
        s = s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        // Схлопываем лишние пустые строки (Word плодит их между абзацами).
        s = s.replace(Regex("[ \\t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    // --- Имя/заголовок --------------------------------------------------------

    private fun displayName(context: Context, uri: Uri): String {
        var name = "import"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        }
        return name
    }

    private fun titleFrom(fileName: String): String {
        val base = fileName.substringBeforeLast('.').trim()
        return if (base.length <= 60) base else base.substring(0, 60)
    }
}
