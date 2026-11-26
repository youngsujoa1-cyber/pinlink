package com.youngsu.pinlink.ui

import android.content.Context
import com.youngsu.pinlink.R
import com.youngsu.pinlink.core.PinLogic

/**
 * 체류 시간 문구 — 정지해 있을 때 "12분째 머무름" / 1분 미만이면 "방금 도착".
 *
 * 이동 중이면 화면은 속도를 보여 주고([stayText] 는 쓰지 않는다),
 * 서 있을 때만 이 문구를 쓴다. [short] 는 지도 위 작은 칩용("12분째").
 */
fun stayText(context: Context, since: Long, now: Long, short: Boolean = false): String {
    val stay = PinLogic.stay(since, now)
    if (stay.justArrived) return context.getString(R.string.stay_just)
    val hours = stay.hours.toInt()
    val minutes = stay.minutes.toInt()
    return if (short) {
        if (hours > 0) context.getString(R.string.stay_short_hour, hours)
        else context.getString(R.string.stay_short_min, minutes)
    } else {
        if (hours > 0) context.getString(R.string.stay_hour, hours, minutes)
        else context.getString(R.string.stay_min, minutes)
    }
}
