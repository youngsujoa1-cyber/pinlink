package com.youngsu.pinlink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 리소스(아이콘·벡터 그림)가 실제 안드로이드 파서로 읽히는지 본다.
 *
 * v1.0.0 은 벡터 pathData 의 호(arc) 명령에 붙여 쓴 플래그 때문에
 * 기기에서 ic_place 를 읽는 순간 앱이 죽고, 런처 아이콘도 기본 안드로이드 아이콘으로 보였다.
 * Robolectric 의 기본(legacy) 그래픽은 이 오류를 그냥 넘겨서 못 잡았으므로
 * 여기서는 NATIVE(실제 구현) 그래픽 모드로 전부 읽어 본다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResourceSmokeTest {

    private fun ids(className: String): List<Pair<String, Int>> {
        val cls = Class.forName(className)
        return cls.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .map { field -> field.name to field.getInt(null) }
    }

    @Test
    fun everyDrawableInflates() {
        val app = RuntimeEnvironment.getApplication()
        val failures = ArrayList<String>()
        for ((name, id) in ids("com.youngsu.pinlink.R\$drawable")) {
            try {
                val drawable = app.getDrawable(id)
                if (drawable == null) failures += "$name: null"
            } catch (error: Throwable) {
                failures += "$name: ${error.javaClass.simpleName} ${error.message}"
            }
        }
        assertTrue("읽지 못한 drawable: $failures", failures.isEmpty())
    }

    @Test
    fun everyMipmapInflates() {
        val app = RuntimeEnvironment.getApplication()
        val failures = ArrayList<String>()
        for ((name, id) in ids("com.youngsu.pinlink.R\$mipmap")) {
            try {
                val drawable = app.getDrawable(id)
                if (drawable == null) failures += "$name: null"
            } catch (error: Throwable) {
                failures += "$name: ${error.javaClass.simpleName} ${error.message}"
            }
        }
        assertTrue("읽지 못한 mipmap: $failures", failures.isEmpty())
    }

    @Test
    fun launcherIconRenders() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getApplicationInfo(app.packageName, 0)
        assertTrue("아이콘 리소스가 있어야 한다", info.icon != 0)
        val drawable: Drawable = requireNotNull(app.getDrawable(info.icon)) { "아이콘 drawable 이 그려져야 한다" }

        drawable.setBounds(0, 0, 432, 432)
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))

        val out = File("/workspace/work/pinlink-icon-render.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val center = bitmap.getPixel(216, 216)
        val alpha = (center ushr 24) and 0xFF
        assertTrue("아이콘 가운데가 비어 있으면 안 된다 (alpha=$alpha)", alpha > 200)
    }

    @Test
    fun notificationIconRenders() {
        val app = RuntimeEnvironment.getApplication()
        val drawable = requireNotNull(app.getDrawable(R.drawable.ic_notification))
        drawable.setBounds(0, 0, 96, 96)
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        assertTrue(bitmap.getPixel(48, 30) != 0 || bitmap.getPixel(48, 48) != 0)
    }
}
