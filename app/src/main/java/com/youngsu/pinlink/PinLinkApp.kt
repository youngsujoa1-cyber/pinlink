package com.youngsu.pinlink

import android.app.Application
import com.youngsu.pinlink.ads.AdManager

/**
 * 앱 프로세스가 만들어질 때 하는 일은 최대한 적게 둔다.
 *
 * 예전(v1.0.0)에는 여기서 상태 객체(PinSession)까지 초기화해서, 화면이 뜨기 전에
 * 무슨 일이 생기면 사용자에게는 "앱에 버그가 있어 종료했습니다"로만 보였다.
 * 이제는 [CrashLog] 설치와 광고 초기화(감싼 호출)만 하고,
 * 방·위치 상태는 화면(MainActivity)과 위치 서비스(ShareService)가 뜰 때 각자 초기화한다.
 */
class PinLinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 크래시를 기기에 적어 둔다 — 다음 실행 때 화면에 보여 준다
        runCatching { CrashLog.install(this) }
        // 광고는 실패해도 앱 시작을 막지 않는다 (AdManager 안에서 예외를 잡는다)
        runCatching { AdManager.initialize(this) }
    }
}
