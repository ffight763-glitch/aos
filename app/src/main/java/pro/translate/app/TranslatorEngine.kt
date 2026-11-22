package pro.translate.app

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/** Offline translation on the device (Google ML Kit). Models (~30 MB each) download once. */
class TranslatorEngine {
    private val translators = HashMap<Pair<String, String>, Translator>()
    private val models = RemoteModelManager.getInstance()
    private val langId = LanguageIdentification.getClient()

    /** Returns a supported language code or null. */
    suspend fun detect(text: String): String? {
        val tag = runCatching { langId.identifyLanguage(text).await() }.getOrNull() ?: return null
        if (tag == "und") return null
        return TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
    }

    suspend fun isDownloaded(code: String): Boolean =
        runCatching { models.isModelDownloaded(model(code)).await() }.getOrDefault(false)

    suspend fun translate(text: String, from: String, to: String): String {
        if (from == to || text.isBlank()) return text
        val t = synchronized(translators) {
            translators.getOrPut(from to to) {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
                )
            }
        }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return t.translate(text).await()
    }

    suspend fun downloaded(): Set<String> =
        runCatching {
            models.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()
        }.getOrDefault(emptySet())

    suspend fun download(code: String) {
        models.download(model(code), DownloadConditions.Builder().build()).await()
    }

    suspend fun delete(code: String) {
        if (code == TranslateLanguage.ENGLISH) return // English is required as a pivot
        models.deleteDownloadedModel(model(code)).await()
    }

    private fun model(code: String) = TranslateRemoteModel.Builder(code).build()

    fun close() {
        synchronized(translators) { translators.values.forEach { it.close() }; translators.clear() }
        langId.close()
    }
}
