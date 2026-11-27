package com.youngsu.pinlink.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 지도 핀 색 (v1.0.10).
 *
 * 아바타 색은 다크 UI 위에서 고급스럽게 보이도록 일부러 **연한 파스텔 톤**을 쓴다
 * ([com.youngsu.pinlink.data.AvatarPalette]). 그런데 같은 색을 지도에 그대로 올리면
 * 밝은 네이버·CARTO 지도 위에서 흰 글자와 함께 거의 사라져, 상대방 핀이 어디 있는지
 * 한눈에 보이지 않았다(2026-11-27 실기기: `홍` 핀이 연분홍 반투명으로 흐릿).
 *
 * 그래서 지도에 그릴 때만 같은 색상(hue)을 유지한 채 **채도·명도를 끌어올린 선명한 색**으로
 * 바꾼다. 흰 3px 테두리·진한 그림자와 함께 밝은 지도·어두운 지도 어디서나 또렷하게 읽힌다.
 * (안드로이드에 기대지 않는 순수 로직 — JVM 검사에서 그대로 시험한다)
 */
object PinColors {

    /** 지도에서 쓸 기본 색 — 아바타 색을 못 읽었을 때(샴페인 골드의 선명한 판) */
    const val DEFAULT = "#F0A500"

    /** 아바타 팔레트 8색 → 지도용 선명한 색. 색상은 그대로 두고 채도·명도만 올렸다 */
    private val PALETTE: Map<String, String> = mapOf(
        "#E5C158" to "#E8A008", // champagne → amber
        "#B9A7FF" to "#7C3AED", // orchid → violet
        "#6FE3FF" to "#0E86C9", // cyan → blue-cyan
        "#C9D3E0" to "#475569", // platinum → slate
        "#7FDCC0" to "#059669", // jade → emerald
        "#F2A9B4" to "#FF2E63", // rose → hot pink
        "#F0B183" to "#EA580C", // copper → orange
        "#9FB4D8" to "#2563EB"  // steel → blue
    )

    /** 지도 핀 색 — 팔레트면 표에서, 그 밖의 색이면 같은 색상으로 채도를 올린다 */
    fun vivid(hex: String?): String {
        val text = canonical(hex) ?: return DEFAULT
        PALETTE[text]?.let { return it }
        val rgb = parse(text) ?: return DEFAULT
        return boost(rgb[0], rgb[1], rgb[2])
    }

    /** `#f2a9b4`·`#FFF2A9B4`·`#abc` → `#RRGGBB` (모르는 모양이면 null) */
    fun canonical(hex: String?): String? {
        var body: String = hex?.trim()?.removePrefix("#")?.removePrefix("#").orEmpty()
        if (body.length == 8) body = body.substring(2)   // #AARRGGBB — 알파는 버린다
        if (body.length == 3) body = buildString { for (c in body) { append(c); append(c) } }
        if (body.length != 6) return null
        if (!body.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return "#" + body.uppercase()
    }

    /** 이름 첫 글자 배지 등 글자색 — 선명한 배경 위에서는 흰색이 가장 또렷하다 */
    fun onPin(hex: String?): String = "#FFFFFF"

    /** #RRGGBB / #AARRGGBB(맨 앞 알파는 무시) → [r, g, b] */
    fun parse(hex: String): IntArray? {
        var body = hex.trim().removePrefix("#")
        if (body.length == 8) body = body.substring(2)   // #AARRGGBB
        if (body.length == 6) {
            val value = body.toLongOrNull(16) ?: return null
            return intArrayOf(
                ((value shr 16) and 0xFF).toInt(),
                ((value shr 8) and 0xFF).toInt(),
                (value and 0xFF).toInt()
            )
        }
        if (body.length == 3) {
            val expanded = buildString { for (c in body) { append(c); append(c) } }
            return parse("#$expanded")
        }
        return null
    }

    /**
     * 같은 색상으로 채도 0.78 이상 · 밝기 0.42~0.58(HSL)로 올린다.
     * 회색처럼 색상이 없는 색은 채도를 올릴 수 없으므로 밝기만 낮춰(짙은 회청색) 대비를 준다.
     */
    fun boost(r: Int, g: Int, b: Int): String {
        val hsl = toHsl(r, g, b)
        val hue = hsl[0]
        val saturation = hsl[1]
        val light = hsl[2]
        val newSaturation = if (saturation < 0.08) 0.10 else maxOf(saturation, 0.78)
        val newLight = light.coerceIn(0.40, 0.52)
        val rgb = fromHsl(hue, newSaturation, newLight)
        return hex(rgb[0], rgb[1], rgb[2])
    }

    /** 두 색의 거리(성분 최대 차) — 검사에서 '원래보다 진해졌는지' 볼 때 쓴다 */
    fun channelSpread(hex: String): Int {
        val rgb = parse(hex) ?: return 0
        return maxOf(rgb[0], rgb[1], rgb[2]) - minOf(rgb[0], rgb[1], rgb[2])
    }

    /** RGB → HSL (h 0~360, s 0~1, l 0~1) */
    fun toHsl(r: Int, g: Int, b: Int): DoubleArray {
        val rf = r / 255.0
        val gf = g / 255.0
        val bf = b / 255.0
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val l = (max + min) / 2.0
        if (abs(max - min) < 1e-9) return doubleArrayOf(0.0, 0.0, l)
        val d = max - min
        val s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
        val h = when (max) {
            rf -> ((gf - bf) / d + if (gf < bf) 6.0 else 0.0)
            gf -> ((bf - rf) / d + 2.0)
            else -> ((rf - gf) / d + 4.0)
        } * 60.0
        return doubleArrayOf(h, s, l)
    }

    /** HSL → RGB (0~255) */
    fun fromHsl(h: Double, s: Double, l: Double): IntArray {
        val c = (1.0 - abs(2.0 * l - 1.0)) * s
        val hp = ((h % 360.0) + 360.0) % 360.0 / 60.0
        val x = c * (1.0 - abs(hp % 2.0 - 1.0))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0.0)
            hp < 2 -> Triple(x, c, 0.0)
            hp < 3 -> Triple(0.0, c, x)
            hp < 4 -> Triple(0.0, x, c)
            hp < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2.0
        return intArrayOf(
            ((r1 + m) * 255.0).roundToInt().coerceIn(0, 255),
            ((g1 + m) * 255.0).roundToInt().coerceIn(0, 255),
            ((b1 + m) * 255.0).roundToInt().coerceIn(0, 255)
        )
    }

    fun hex(r: Int, g: Int, b: Int): String =
        String.format(java.util.Locale.US, "#%02X%02X%02X", r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}
