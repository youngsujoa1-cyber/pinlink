package com.youngsu.pinlink.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.youngsu.pinlink.R
import com.youngsu.pinlink.data.AppLanguage
import com.youngsu.pinlink.data.AppPrefs
import com.youngsu.pinlink.data.PinSession
import com.youngsu.pinlink.data.Sync

/** 설정 — 프로필, 위치 권한 상태, 앱 언어, 개인정보 처리방침, 광고·버전 안내 */
class SettingsScreen(private val host: MainActivity) {

    private val act = host

    val view: View

    private val column: LinearLayout
    private val avatar: TextView
    private val nameText: TextView
    private val locationStatus: TextView
    private val languageValue: TextView
    private val syncNote: TextView
    private val permissionNote: TextView

    init {
        val scroll = NestedScrollView(act).apply { isFillViewport = true; clipToPadding = false }
        view = scroll
        column = Ui.column(act).apply {
            setPadding(act.dp(16f), act.dp(18f), act.dp(16f), act.dp(24f))
        }
        scroll.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        column.addView(
            Ui.text(act, act.getString(R.string.settings_title), 22f, act.getColor(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // ---------------------------------------------------------- 프로필 카드
        val profile = card()
        val profileRow = Ui.row(act)
        avatar = Ui.text(act, "?", 18f, Color.parseColor("#03231C"), bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(act.getColor(R.color.accent))
            }
        }
        profileRow.addView(avatar, LinearLayout.LayoutParams(act.dp(48f), act.dp(48f)).apply { rightMargin = act.dp(13f) })
        val profileTexts = Ui.column(act)
        nameText = Ui.text(act, "", 16f, act.getColor(R.color.text_primary), bold = true)
        profileTexts.addView(nameText)
        profileTexts.addView(
            Ui.text(act, act.getString(R.string.settings_edit_profile), 11.5f, act.getColor(R.color.text_secondary)).apply {
                setPadding(0, act.dp(2f), 0, 0)
            }
        )
        profileRow.addView(profileTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        profileRow.addView(Ui.icon(act, R.drawable.ic_chevron_right, 18f, act.getColor(R.color.text_tertiary)))
        Ui.clickable(profileRow) {
            Dialogs.showCreate(
                act,
                title = act.getString(R.string.settings_profile),
                sub = act.getString(R.string.create_sub)
            ) { name, color ->
                PinSession.saveProfile(name, color)
                refresh()
            }
        }
        profile.addView(profileRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        column.addView(profile, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(14f) })

        // -------------------------------------------------------------- 권한·위치
        column.addView(
            Ui.sectionTitle(act, act.getString(R.string.settings_location)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = act.dp(20f)
                leftMargin = act.dp(4f)
            }
        )
        val locationCard = card()
        locationStatus = Ui.text(act, "", 12.5f, act.getColor(R.color.accent), bold = true)
        locationCard.addView(locationStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        permissionNote = Ui.text(act, act.getString(R.string.bg_disclosure_body), 11f, act.getColor(R.color.text_tertiary)).apply {
            setPadding(0, act.dp(8f), 0, 0)
        }
        locationCard.addView(permissionNote, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val openSettings = Ui.button(act, act.getString(R.string.settings_open_system), primary = false) {
            openSystemSettings()
        }
        locationCard.addView(openSettings, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(12f) })
        column.addView(locationCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(8f) })

        // --------------------------------------------------------------- 앱 언어
        val languageCard = card()
        val languageRow = Ui.row(act)
        languageRow.addView(iconBadge(R.drawable.ic_language), LinearLayout.LayoutParams(act.dp(34f), act.dp(34f)).apply {
            rightMargin = act.dp(12f)
        })
        languageRow.addView(
            Ui.text(act, act.getString(R.string.settings_language), 14f, act.getColor(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        )
        languageValue = Ui.text(act, "", 12f, act.getColor(R.color.text_secondary))
        languageRow.addView(languageValue, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = act.dp(6f) })
        languageRow.addView(Ui.icon(act, R.drawable.ic_chevron_right, 18f, act.getColor(R.color.text_tertiary)))
        Ui.clickable(languageRow) { showLanguagePicker() }
        languageCard.addView(languageRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        column.addView(languageCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(12f) })

        // --------------------------------------------------------- 개인정보 처리방침
        val privacyCard = card()
        val privacyRow = Ui.row(act)
        privacyRow.addView(iconBadge(R.drawable.ic_shield), LinearLayout.LayoutParams(act.dp(34f), act.dp(34f)).apply {
            rightMargin = act.dp(12f)
        })
        privacyRow.addView(
            Ui.text(act, act.getString(R.string.settings_privacy), 14f, act.getColor(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        )
        privacyRow.addView(Ui.icon(act, R.drawable.ic_chevron_right, 18f, act.getColor(R.color.text_tertiary)))
        Ui.clickable(privacyRow) { act.startActivity(Intent(act, PrivacyActivity::class.java)) }
        privacyCard.addView(privacyRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        column.addView(privacyCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(12f) })

        // ------------------------------------------------------------------ 정보
        column.addView(
            Ui.sectionTitle(act, act.getString(R.string.settings_about)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = act.dp(20f)
                leftMargin = act.dp(4f)
            }
        )
        val about = card()
        about.addView(
            Ui.text(act, act.getString(R.string.settings_version, buildVersionName()), 12.5f, act.getColor(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        syncNote = Ui.text(
            act,
            act.getString(if (Sync.isDemo) R.string.settings_sync_demo else R.string.settings_sync_live),
            11f,
            act.getColor(R.color.text_secondary)
        ).apply { setPadding(0, act.dp(4f), 0, 0) }
        about.addView(syncNote, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        about.addView(
            Ui.text(act, act.getString(R.string.about_body), 11.5f, act.getColor(R.color.text_tertiary)).apply {
                setPadding(0, act.dp(10f), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        about.addView(
            Ui.text(act, act.getString(R.string.settings_ads), 11.5f, act.getColor(R.color.text_secondary), bold = true).apply {
                setPadding(0, act.dp(14f), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        about.addView(
            Ui.text(act, act.getString(R.string.settings_ads_note), 11f, act.getColor(R.color.text_tertiary)).apply {
                setPadding(0, act.dp(4f), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        column.addView(about, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(8f) })
    }

    fun refresh() {
        val color = PinSession.myColor
        avatar.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(runCatching { Color.parseColor(color) }.getOrDefault(act.getColor(R.color.accent)))
        }
        avatar.text = PinSession.myName.trim().firstOrNull()?.toString()?.uppercase() ?: "?"
        nameText.text = PinSession.myName

        locationStatus.text = when {
            PinSession.hasBackgroundPermission() -> act.getString(R.string.settings_location_allowed)
            PinSession.hasForegroundPermission() -> act.getString(R.string.settings_location_foreground)
            else -> act.getString(R.string.settings_location_denied)
        }
        locationStatus.setTextColor(
            act.getColor(
                when {
                    PinSession.hasBackgroundPermission() -> R.color.accent
                    PinSession.hasForegroundPermission() -> R.color.warn
                    else -> R.color.danger
                }
            )
        )

        val tag = AppLanguage.currentTag()
        languageValue.text = if (tag.isEmpty()) {
            act.getString(R.string.language_system)
        } else {
            AppLanguage.NATIVE[tag] ?: tag
        }
        syncNote.text = act.getString(if (Sync.isDemo) R.string.settings_sync_demo else R.string.settings_sync_live)
        permissionNote.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ 부품

    private fun showLanguagePicker() {
        val tags = AppLanguage.TAGS
        val labels = tags.map { tag ->
            if (tag.isEmpty()) "🌐  ${act.getString(R.string.language_system)}" else AppLanguage.NATIVE[tag] ?: tag
        }.toTypedArray()
        val checked = tags.indexOf(AppLanguage.currentTag()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(act)
            .setTitle(act.getString(R.string.settings_language))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val tag = tags.getOrNull(which) ?: return@setSingleChoiceItems
                AppPrefs.get(act).languageOnboarded = true
                // 앱 언어가 바뀌면 화면이 새 언어로 다시 만들어진다
                AppLanguage.apply(tag)
            }
            .setNegativeButton(act.getString(R.string.cancel), null)
            .show()
    }

    private fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", act.packageName, null)
        }
        runCatching { act.startActivity(intent) }
    }

    private fun iconBadge(iconRes: Int): View = LinearLayout(act).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = act.dpf(11f)
            setColor(act.getColor(R.color.accent_dim))
        }
        addView(Ui.icon(act, iconRes, 18f, act.getColor(R.color.accent)), LinearLayout.LayoutParams(act.dp(18f), act.dp(18f)))
    }

    private fun buildVersionName(): String =
        runCatching {
            act.packageManager.getPackageInfo(act.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"

    private fun card(): LinearLayout = Ui.column(act).apply {
        background = Ui.rounded(
            act,
            act.getColor(R.color.surface_alt),
            20f,
            act.getColor(R.color.outline),
            ripple = false
        )
        setPadding(act.dp(16f), act.dp(16f), act.dp(16f), act.dp(16f))
    }
}
