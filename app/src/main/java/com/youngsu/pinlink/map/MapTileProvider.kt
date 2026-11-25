package com.youngsu.pinlink.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.youngsu.pinlink.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 실제 도로·지명이 그려진 지도 타일(z/x/y)을 받아 캐시하는 엔진.
 *
 * · CARTO 베이스맵의 **512×512 @2x 고해상도 타일**을 기본으로 쓴다(키는 local.properties 의 CARTO_KEY).
 *   키가 없거나 CARTO 가 막히면 OpenStreetMap 표준 타일(256×256)로 넘어간다.
 * · 스타일별로 메모리 LruCache 와 디스크 캐시(cacheDir/pinlink-tiles/v2_dark · v2_light)를 나눠 쓴다.
 *   v1.0.1 의 저해상도 OSM 캐시(pinlink-tiles/v1)는 새 버전 첫 실행 때 지운다.
 * · 서버가 막히면 다음 미러로 넘어가고, 모두 실패하면 화면이 격자 바탕만 그린다.
 *
 * 타일 서버 이용 정책상 앱을 밝히는 User-Agent 를 반드시 붙인다.
 */
class MapTileProvider private constructor(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(POOL_SIZE) { r ->
        Thread(r, "pinlink-map-tile").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val mem = object : LruCache<String, Bitmap>(memoryBudget()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val lock = Any()
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    private var downloads = 0

    init {
        // v1.0.1 이 남긴 256×256 저해상도 캐시는 더 쓰지 않는다 — 조용히 지운다
        pool.execute {
            runCatching { legacyDir().deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------ 타일

    /** 메모리 캐시에 있으면 즉시 돌려주고, 없으면 내려받기를 예약한다(끝나면 notify 호출). */
    fun tile(style: MapStyle, z: Int, x: Int, y: Int, notify: () -> Unit): Bitmap? {
        val key = key(style, z, x, y)
        mem.get(key)?.let { return it }

        synchronized(lock) {
            if (key in pending || pending.size >= MAX_PENDING) return null
            val failed = failedAt[key]
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null
            pending.add(key)
        }

        pool.execute {
            val bmp = readDisk(style, key) ?: download(style, z, x, y)
            synchronized(lock) {
                pending.remove(key)
                if (bmp == null) failedAt[key] = System.currentTimeMillis() else failedAt.remove(key)
            }
            if (bmp != null) {
                mem.put(key, bmp)
                main.post { notify() }
                if (++downloads % 60 == 0) pruneDiskAsync(style)
            }
        }
        return null
    }

    /** 화면 밖 타일을 미리 받아 둔다 — 팬·줌이 끊기지 않게. 실패해도 화면에는 알리지 않는다. */
    fun prefetch(style: MapStyle, z: Int, x: Int, y: Int) {
        val key = key(style, z, x, y)
        if (mem.get(key) != null) return
        synchronized(lock) {
            if (key in pending || pending.size >= MAX_PENDING) return
            val failed = failedAt[key]
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return
            pending.add(key)
        }
        pool.execute {
            val bmp = readDisk(style, key) ?: download(style, z, x, y)
            synchronized(lock) {
                pending.remove(key)
                if (bmp == null) failedAt[key] = System.currentTimeMillis() else failedAt.remove(key)
            }
            if (bmp != null) mem.put(key, bmp)
        }
    }

    /** 메모리에 있는 타일만 본다 — 줌이 바뀌는 순간 부모 타일 폴백에 쓴다 */
    fun cached(style: MapStyle, z: Int, x: Int, y: Int): Bitmap? = mem.get(key(style, z, x, y))

    // ------------------------------------------------------------------ files

    private fun key(style: MapStyle, z: Int, x: Int, y: Int) = "${style.id}/$z/$x/$y"

    private fun legacyDir() = File(context.cacheDir, "pinlink-tiles/v1")

    private fun dirOf(style: MapStyle) = File(context.cacheDir, "pinlink-tiles/${style.cacheDir}")

    private fun fileOf(style: MapStyle, key: String): File {
        val parts = key.split('/') // style/z/x/y
        return File(File(dirOf(style), parts[1]), "${parts[2]}_${parts[3]}.png")
    }

    private fun readDisk(style: MapStyle, key: String): Bitmap? {
        val f = fileOf(style, key)
        if (!f.isFile || f.length() < 64L) return null
        return decode(readBytes(f)) ?: run { f.delete(); null }
    }

    private fun readBytes(file: File): ByteArray? = runCatching { file.readBytes() }.getOrNull()

    private fun decode(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.size < 64) return null
        val options = BitmapFactory.Options().apply {
            inScaled = false          // 화면 배율에 따라 임의로 줄이지 않는다
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

    private fun saveDisk(style: MapStyle, key: String, bytes: ByteArray) {
        runCatching {
            val f = fileOf(style, key)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            f.setLastModified(System.currentTimeMillis())
        }
    }

    // --------------------------------------------------------------- network

    private fun download(style: MapStyle, z: Int, x: Int, y: Int): Bitmap? {
        // 1) CARTO @2x (512×512) — 키가 없거나 만료되면 'API KEY REQUIRED' 256×256 이 오므로 크기로 거른다
        for (url in listOfNotNull(TileUrl.carto(style, z, x, y, BuildConfig.CARTO_KEY))) {
            val bytes = http(url) ?: continue
            val bmp = decode(bytes) ?: continue
            if (bmp.width < MIN_RETINA_PX) continue
            saveDisk(style, key(style, z, x, y), bytes)
            return bmp
        }
        // 2) CARTO 가 안 되면 OpenStreetMap 표준 타일(256×256)
        for (url in TileUrl.osm(z, x, y)) {
            val bytes = http(url) ?: continue
            val bmp = decode(bytes) ?: continue
            if (bmp.width < MIN_1X_PX) continue
            saveDisk(style, key(style, z, x, y), bytes)
            return bmp
        }
        return null
    }

    private fun http(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "image/png,image/*;q=0.8")
                setRequestProperty("Accept-Encoding", "identity")
            }
            if (conn.responseCode != 200) return null
            val type = conn.contentType ?: ""
            if (!type.contains("image", ignoreCase = true)) return null
            conn.inputStream.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ------------------------------------------------------------ housekeeping

    private fun pruneDiskAsync(style: MapStyle) {
        pool.execute {
            runCatching {
                val dir = dirOf(style)
                val files = dir.walkTopDown().filter { it.isFile }.toMutableList()
                var total = files.sumOf { it.length() }
                if (total <= DISK_BUDGET) return@runCatching
                files.sortBy { it.lastModified() }
                for (f in files) {
                    if (total <= DISK_BUDGET * 3 / 4) break
                    total -= f.length()
                    f.delete()
                }
            }
        }
    }

    private fun memoryBudget(): Int {
        val max = Runtime.getRuntime().maxMemory() / 6
        // 512×512 한 장이 약 1MB — 화면 두세 판을 넉넉히 들고 있을 만큼 잡는다
        return max.coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024).toInt()
    }

    companion object {
        /** 타일 정책이 요구하는 앱 식별자 */
        private const val USER_AGENT = "PinLink/1.0.2 (Android; com.youngsu.pinlink)"

        private const val RETRY_MS = 60_000L
        private const val DISK_BUDGET = 64L * 1024 * 1024
        private const val POOL_SIZE = 6
        private const val MAX_PENDING = 48

        /** @2x 타일은 512×512 — 이보다 작으면 키가 빠진 워터마크 타일로 본다 */
        private const val MIN_RETINA_PX = 480
        private const val MIN_1X_PX = 200

        /** CARTO 키가 빌드에 들어갔는지 — 지도 저작권 표시를 정하는 데 쓴다 */
        val USES_CARTO: Boolean get() = BuildConfig.CARTO_KEY.isNotBlank()

        @Volatile private var instance: MapTileProvider? = null

        fun get(context: Context): MapTileProvider {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val made = MapTileProvider(context.applicationContext)
                instance = made
                return made
            }
        }
    }
}
