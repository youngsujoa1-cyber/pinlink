package com.youngsu.pinlink.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 서버 통신 기록 — 앱 안에서 마지막 120줄만 남긴다.
 *
 * v1.0.4 까지는 실패가 화면의 "서버에 연결하지 못했어요" 한 줄로 끝나서, 기기에서 무슨 일이
 * 있었는지(권한 거부인지, 시간 초과인지, 404 인지) 알 방법이 없었다.
 * 이제 모든 요청이 여기에 남고, 설정 → 동기화 진단에서 그대로 보이며 복사해서 보낼 수 있다.
 */
object SyncLog {

    private const val MAX_LINES = 120
    private const val FILE_NAME = "sync-log.txt"

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val stampLast = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val target = File(context.applicationContext.filesDir, FILE_NAME)
            runCatching {
                if (target.isFile) {
                    target.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
                }
            }
            file = target
        }
    }

    fun record(tag: String, detail: String) {
        val stamp = synchronized(stampLast) { stampLast.format(Date()) }
        val line = "$stamp  $tag  ${detail.replace('\n', ' ').take(220)}"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            runCatching { file?.writeText(lines.joinToString("\n")) }
        }
    }

    /** 최근 기록 (오래된 것 → 최신) */
    fun tail(count: Int = 12): List<String> = synchronized(lock) {
        lines.toList().takeLast(count)
    }

    fun size(): Int = synchronized(lock) { lines.size }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            runCatching { file?.delete() }
        }
    }

    /** 복사·전송용 한 덩어리 */
    fun dump(): String = synchronized(lock) { lines.joinToString("\n") }
}
