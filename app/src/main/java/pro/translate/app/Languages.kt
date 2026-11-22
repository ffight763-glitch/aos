package pro.translate.app

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

const val AUTO = "auto"

data class Lang(val code: String, val name: String)

object Languages {
    private val ru = Locale("ru")

    val all: List<Lang> = TranslateLanguage.getAllLanguages()
        .map { Lang(it, displayName(it)) }
        .sortedBy { it.name }

    fun displayName(code: String): String {
        if (code == AUTO) return "Автоопределение"
        val n = Locale.forLanguageTag(code).getDisplayName(ru)
        return n.replaceFirstChar { it.titlecase(ru) }
    }

    /** Locale for speech/TTS. */
    fun locale(code: String): Locale = when (code) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "he" -> Locale("iw")
        else -> Locale.forLanguageTag(code)
    }
}
