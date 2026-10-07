package io.github.hansoda.trace.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.IllformedLocaleException
import java.util.Locale

/**
 * Trace's own language: English unless Russian is chosen, whatever the phone is set to. The
 * phone's region still decides the order of dates and the units.
 */
enum class AppLanguage(val tag: String, /** The language's name for itself, as the choice shows it. */ val nativeName: String) {
    ENGLISH("en", "English"),
    RUSSIAN("ru", "Русский"),
    ;

    companion object {
        private const val PREFS = "language"
        private const val KEY = "language"

        fun current(context: Context): AppLanguage {
            val tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.tag == tag } ?: ENGLISH
        }

        fun choose(context: Context, language: AppLanguage) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
        }

        /** The chosen language in the phone's region, such as English in Russia. */
        fun locale(context: Context): Locale {
            val language = current(context).tag
            val region = Resources.getSystem().configuration.locales[0].country
            return try {
                Locale.Builder().setLanguage(language).setRegion(region).build()
            } catch (_: IllformedLocaleException) {
                Locale.forLanguageTag(language)
            }
        }

        /**
         * [base] in Trace's language. It also becomes the default locale, which dates formatted
         * without a context follow.
         */
        fun apply(base: Context): Context {
            val locale = locale(base)
            Locale.setDefault(locale)
            val configuration = Configuration(base.resources.configuration)
            configuration.setLocales(LocaleList(locale))
            return base.createConfigurationContext(configuration)
        }
    }
}
