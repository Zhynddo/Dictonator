package com.ngan.dichthuat

import android.content.Context
import android.net.Uri
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

const val AUTO = "auto"

data class Lang(val code: String, val name: String, val speech: String)

object Langs {
    // Codes are ML Kit translation codes.
    val all = listOf(
        Lang("vi", "Tiếng Việt", "vi-VN"),
        Lang("en", "English", "en-US"),
        Lang("zh", "Chinese 中文", "zh-CN"),
        Lang("ja", "Japanese 日本語", "ja-JP"),
        Lang("ko", "Korean 한국어", "ko-KR"),
        Lang("th", "Thai ไทย", "th-TH"),
        Lang("fr", "French", "fr-FR"),
        Lang("de", "German", "de-DE"),
        Lang("es", "Spanish", "es-ES"),
        Lang("it", "Italian", "it-IT"),
        Lang("pt", "Portuguese", "pt-BR"),
        Lang("ru", "Russian", "ru-RU"),
        Lang("id", "Indonesian", "id-ID"),
        Lang("ms", "Malay", "ms-MY"),
        Lang("hi", "Hindi", "hi-IN"),
        Lang("ar", "Arabic", "ar-SA"),
        Lang("nl", "Dutch", "nl-NL"),
        Lang("pl", "Polish", "pl-PL"),
        Lang("tr", "Turkish", "tr-TR"),
        Lang("uk", "Ukrainian", "uk-UA"),
    )
    private val byCode = all.associateBy { it.code }
    fun name(code: String): String = if (code == AUTO) "Detect language" else byCode[code]?.name ?: code
    fun speech(code: String): String = byCode[code]?.speech ?: code
    fun supported(code: String) = code in byCode
}

/** Everything here runs on the phone. */
class Engine {
    private val models = RemoteModelManager.getInstance()
    private val translators = mutableMapOf<String, Translator>()
    private val langId = LanguageIdentification.getClient()
    private val ocrClient = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private fun model(code: String) = TranslateRemoteModel.Builder(code).build()

    suspend fun downloadedLanguages(): Set<String> =
        models.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet() + "en"

    suspend fun download(code: String) {
        models.download(model(code), DownloadConditions.Builder().build()).await()
    }

    suspend fun delete(code: String) {
        models.deleteDownloadedModel(model(code)).await()
    }

    suspend fun translate(text: String, from: String, to: String): String {
        if (from == to) return text
        val t = translators.getOrPut("$from>$to") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
            )
        }
        return t.translate(text).await()
    }

    /** Returns a supported language code, or null if unsure. */
    suspend fun detect(text: String): String? {
        if (VIET.containsMatchIn(text)) return "vi"
        val raw = langId.identifyLanguage(text).await()
        if (raw == "und") return null
        if (Langs.supported(raw)) return raw
        val base = raw.substringBefore('-')
        return if (Langs.supported(base)) base else null
    }

    suspend fun readText(context: Context, uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)
        return ocrClient.process(image).await().text
    }

    fun close() {
        translators.values.forEach { it.close() }
        langId.close()
        ocrClient.close()
    }

    companion object {
        val VIET = Regex(
            "[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]",
            RegexOption.IGNORE_CASE
        )
    }
}
