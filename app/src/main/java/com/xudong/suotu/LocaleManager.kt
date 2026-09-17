package com.xudong.suotu

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** UI language: follow the system, or force one. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    CHINESE("zh"),
    ;

    companion object {
        fun fromName(name: String?): AppLanguage =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * Applies the language override.
 *
 * Uses AppCompat's per-app locale support, which persists the choice itself and
 * recreates activities on change — so a manual override survives restarts without the
 * app storing or re-applying anything at startup.
 */
object LocaleManager {

    fun apply(language: AppLanguage) {
        val locales = language.tag
            ?.let { LocaleListCompat.forLanguageTags(it) }
            ?: LocaleListCompat.getEmptyLocaleList() // empty = follow the system
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** Current override, or SYSTEM when following the device language. */
    fun current(): AppLanguage {
        val tag = AppCompatDelegate.getApplicationLocales()
            .takeIf { !it.isEmpty }
            ?.get(0)?.language
            ?: return AppLanguage.SYSTEM
        return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
    }

    fun label(context: Context, language: AppLanguage): String = context.getString(
        when (language) {
            AppLanguage.SYSTEM -> R.string.language_system
            AppLanguage.ENGLISH -> R.string.language_english
            AppLanguage.CHINESE -> R.string.language_chinese
        }
    )
}
