package com.youngsu.pinlink.data

import android.content.Context
import com.youngsu.pinlink.core.ShareMode
import java.util.UUID

/** 앱 설정·내 프로필·마지막 방 상태 — 앱을 껐다 켜도 이어진다 */
class AppPrefs private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("pinlink_prefs", Context.MODE_PRIVATE)

    /** 첫 실행 언어 선택을 마쳤는지 — false 면 앱을 열 때 언어 카드가 먼저 뜬다 */
    var languageOnboarded: Boolean
        get() = prefs.getBoolean(KEY_LANGUAGE_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_LANGUAGE_ONBOARDED, value).apply()

    /** 내 닉네임 (처음엔 비어 있고 방을 만들 때 정한다) */
    var nickname: String
        get() = prefs.getString(KEY_NICKNAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_NICKNAME, value.trim()).apply()

    /** 아바타 색 — 팔레트의 색 문자열(#RRGGBB) */
    var color: String
        get() = prefs.getString(KEY_COLOR, AvatarPalette.DEFAULT).orEmpty()
        set(value) = prefs.edit().putString(KEY_COLOR, value).apply()

    /** 이 기기의 식별자 — 방 문서의 멤버 ID. 앱을 지우면 새로 생긴다(개인정보 아님) */
    val deviceId: String
        get() {
            val saved = prefs.getString(KEY_DEVICE_ID, null)
            if (saved != null && saved.length >= 8) return saved
            val made = UUID.randomUUID().toString().replace("-", "").take(24)
            prefs.edit().putString(KEY_DEVICE_ID, made).apply()
            return made
        }

    /** 지금 참여 중인 방 코드 (없으면 "") */
    var roomCode: String
        get() = prefs.getString(KEY_ROOM, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ROOM, value).apply()

    var roomOwner: Boolean
        get() = prefs.getBoolean(KEY_ROOM_OWNER, false)
        set(value) = prefs.edit().putBoolean(KEY_ROOM_OWNER, value).apply()

    /** 마지막으로 고른 지도 스타일 (dark / light) */
    var mapStyle: String
        get() = prefs.getString(KEY_MAP_STYLE, "dark").orEmpty()
        set(value) = prefs.edit().putString(KEY_MAP_STYLE, value).apply()

    /** 마지막으로 고른 공유 모드 */
    var shareMode: ShareMode
        get() = ShareMode.fromId(prefs.getString(KEY_MODE, ShareMode.ALWAYS.id))
        set(value) = prefs.edit().putString(KEY_MODE, value.id).apply()

    /** 공유를 시작한 시각 / 자동 종료 시각 (0 이면 제한 없음) */
    var startedAt: Long
        get() = prefs.getLong(KEY_STARTED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_STARTED_AT, value).apply()

    var expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPIRES_AT, value).apply()

    /** 백그라운드 위치 명시적 안내를 한 번 읽고 동의했는지 (Play 정책: 매번 다시 보여 줄 필요는 없지만 설정에서 다시 볼 수 있다) */
    var disclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLOSURE, value).apply()

    fun clearRoom() {
        prefs.edit()
            .remove(KEY_ROOM)
            .remove(KEY_ROOM_OWNER)
            .putLong(KEY_STARTED_AT, 0L)
            .putLong(KEY_EXPIRES_AT, 0L)
            .putString(KEY_MODE, ShareMode.ALWAYS.id)
            .apply()
    }

    companion object {
        private const val KEY_LANGUAGE_ONBOARDED = "language_onboarded"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_COLOR = "avatar_color"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ROOM = "room_code"
        private const val KEY_ROOM_OWNER = "room_owner"
        private const val KEY_MAP_STYLE = "map_style"
        private const val KEY_MODE = "share_mode"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_DISCLOSURE = "disclosure_accepted"

        @Volatile private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(context).also { instance = it }
            }
    }
}

/** 멤버 아바타 색 — 지도 핀·목록·프로필이 같은 색을 쓴다 */
object AvatarPalette {
    val COLORS = listOf(
        "#2BD9A6", // mint
        "#4C8DFF", // sky
        "#FF7A59", // coral
        "#B776FF", // violet
        "#FFC94D", // amber
        "#FF5C8A", // rose
        "#38D0E8", // cyan
        "#8FE05A"  // lime
    )

    val DEFAULT: String = COLORS.first()

    fun next(current: String): String {
        val index = COLORS.indexOf(current)
        return COLORS[(index + 1).mod(COLORS.size)]
    }
}
