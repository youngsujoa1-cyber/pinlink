package com.youngsu.pinlink.data

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 앱 안에서 고르는 언어 — 첫 실행 카드와 설정에서 바꾼다.
 *
 * AppCompatDelegate.setApplicationLocales 가 열려 있는 화면을 모두 새 언어로 다시 만든다.
 * 기기 언어를 따르는 선택은 빈 태그("") 로 표현한다.
 */
object AppLanguage {

    /** 선택 목록 순서 — "" 는 '기기 언어 따르기' */
    val TAGS = listOf("", "ko", "en", "ja", "zh", "es")

    /** 목록에 보이는 이름 — 어느 언어로 열려 있어도 자기 언어를 알아보게 */
    val NATIVE = mapOf(
        "" to "🌐",
        "ko" to "한국어",
        "en" to "English",
        "ja" to "日本語",
        "zh" to "中文",
        "es" to "Español"
    )

    fun currentTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return ""
        val language = locales.toLanguageTags().substringBefore(',').substringBefore('-')
        return if (language in TAGS) language else ""
    }

    fun apply(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    }
}
