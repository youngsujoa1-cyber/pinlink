package com.youngsu.pinlink

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱이 예기치 않게 종료될 때 그 내용을 기기에 적어 둔다.
 *
 * 이 환경에는 실제 기기가 없어 "설치한 폰에서만 나는 크래시"를 재현할 수 없으므로,
 * 다음에 앱을 열었을 때 [read] 로 지난번 오류를 화면에 보여 준다.
 * (사용자는 그 화면을 캡처해 보내기만 하면 되고, 우리는 원인을 바로 찾을 수 있다)
 *
 * 파일은 앱 전용 공간(filesDir, 외부 앱 전용 폴더)에만 남고 다른 앱은 읽지 못한다.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 3500

    @Volatile private var installed = false

    /** 앱 프로세스에서 잡히지 않은 예외를 기록하도록 건다 (기존 핸들러는 그대로 이어 준다) */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 지금 스레드에서 잡은 예외를 기록한다 (앱은 계속 살아 있는 경우) */
    fun record(context: Context, error: Throwable) {
        runCatching { write(context.applicationContext, Thread.currentThread(), error) }
    }

    /** 지난번 오류 기록 (없으면 null) */
    fun read(context: Context): String? {
        for (dir in dirs(context.applicationContext)) {
            val text = runCatching {
                val file = File(dir, FILE_NAME)
                if (file.isFile && file.length() > 0L) file.readText() else null
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    fun clear(context: Context) {
        for (dir in dirs(context.applicationContext)) {
            runCatching { File(dir, FILE_NAME).delete() }
        }
    }

    // ------------------------------------------------------------------ 내부

    private fun dirs(context: Context): List<File> =
        listOfNotNull(
            runCatching { context.filesDir }.getOrNull(),
            runCatching { context.getExternalFilesDir(null) }.getOrNull()
        )

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { writer ->
            PrintWriter(writer).use { error.printStackTrace(it) }
        }.toString()

        val head = buildString {
            append("PinLink ").append(BuildConfig.VERSION_NAME)
            append(" (versionCode ").append(BuildConfig.VERSION_CODE).append(")\n")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("Android ").append(Build.VERSION.RELEASE)
            append(" / API ").append(Build.VERSION.SDK_INT)
            append(" / ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("스레드: ").append(thread.name).append("\n\n")
        }

        val text = (head + trace).take(MAX_CHARS)
        for (dir in dirs(context)) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                File(dir, FILE_NAME).writeText(text)
            }
        }
    }
}
