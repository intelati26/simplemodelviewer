package spcrefs

/** A colour stop: value in shown units and 0xRRGGBB. */
data class Stop(val value: Double, val rgb: Int)

/** Thresholds and the colours of the bands between them (SPC's tables are written that way). */
internal fun bands(thresholds: List<Number>, colours: List<Int>): List<Stop> =
    colours.indices.filter { it < thresholds.size }.map { Stop(thresholds[it].toDouble(), colours[it]) }

/** Palettes ported from the desktop viewer (SPC's own tables for reflectivity, QPF and a few more). */
internal object Palettes {
    val reflectivity = bands(
        (0..24).map { 10.0 + 2.5 * it },
        listOf(0xdae2f1, 0xb5c4e3, 0x90a7d6, 0x6a89c8, 0x476cbb, 0x274fae, 0x254f5d, 0x4e764d, 0x7e9a73, 0xadbf8f, 0xfdf384, 0xf0d46e,
            0xe4b559, 0xd99844, 0xcd7933, 0xc35c24, 0xbb271a, 0x931d15, 0x6c1413, 0x933ea8, 0x772c9b, 0x59198d, 0x828282, 0xb4b4b4))
    val qpf = bands(listOf(0.01, 0.1, 0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3, 4, 5, 7, 10, 15, 20),
        listOf(0xc7e7c0, 0xa1e39b, 0x74c476, 0x31a353, 0x006d2c, 0xfffa8a, 0xffcc4f, 0xfe8d3c, 0xfc4e2a, 0xd61a1c, 0xad0026, 0x690000,
            0xffaafa, 0xff83f9, 0xff57f7, 0xe619f9, 0x9400cc))
    val snow = bands(listOf(0.1, 0.5, 1, 2, 3, 4, 6, 8, 12, 18, 24),
        listOf(0xc6dbef, 0x9ecae1, 0x6baed6, 0x4292c6, 0x2171b5, 0x08519c, 0x08306b, 0x6a51a3, 0x54278f, 0x3f007d))
    val freezingRain = bands(listOf(0.01, 0.05, 0.1, 0.2, 0.3), listOf(0xea3729, 0xb92c1f, 0x892214, 0x581b08))
    val iceAccretion = bands(
        listOf(0.01, 0.02, 0.03, 0.04, 0.05, 0.09, 0.13, 0.17, 0.21, 0.25, 0.3, 0.35, 0.4, 0.5, 0.6, 0.8, 1.0, 1.2, 1.5, 2.0),
        listOf(0xdcdcdc, 0xbebebe, 0x9f9f9f, 0x808080, 0xedc4cc, 0xefabb0, 0xf09296, 0xf2797a, 0xf3605f, 0xc0536e, 0xab516e, 0x974f6f,
            0x824c6f, 0x59407b, 0x443d74, 0x2f3b6c, 0x1a3866, 0x04355e, 0x004b59))
    val probability = bands(listOf(10, 20, 30, 40, 50, 60, 70, 80, 90),
        listOf(0xc6dbef, 0x6baed6, 0x2171b5, 0x08306b, 0xc7e9c0, 0x74c476, 0x238b45, 0xfee391, 0xfe9929))
    val updraftHelicity = bands(listOf(25, 40, 50, 60, 75, 100, 125, 150, 200, 250, 300, 400, 500),
        listOf(0xbebebe, 0x787878, 0x96bef0, 0x5a8ce6, 0x96dc96, 0x3caa50, 0x007800, 0xfff078, 0xf0c828, 0xe69600, 0xd296e6, 0x9646b4, 0x641482))
    val helicity = bands(listOf(50, 100, 150, 200, 300, 400, 500), listOf(0xa1d99b, 0xffff66, 0xfdae61, 0xf46d43, 0xd7191c, 0x9e0142, 0x542788))
    val cape = bands(listOf(100, 250, 500, 1000, 1500, 2000, 2500, 3000, 4000, 5000, 6000),
        listOf(0xc6dbef, 0x9ecae1, 0x74c476, 0xfff078, 0xfdd24a, 0xfdae61, 0xf46d43, 0xd7191c, 0xae017e, 0x6a3d9a))
    val cin = bands(listOf(25, 50, 75, 100, 150, 200, 300), listOf(0xdeebf7, 0xc6dbef, 0x9ecae1, 0x6baed6, 0x3182bd, 0x08519c, 0x08306b))
    fun windSpeed(scale: Double) = bands(listOf(10, 15, 20, 25, 30, 35, 40, 50, 60, 70, 80).map { it * scale },
        listOf(0xdeebf7, 0xc6dbef, 0x9ecae1, 0x74c476, 0xfff078, 0xfdd24a, 0xfdae61, 0xf46d43, 0xd7191c, 0xae017e, 0x6a3d9a))
    val updraftSpeed = bands(listOf(10, 15, 20, 25, 30, 35, 40, 50, 60),
        listOf(0xc6dbef, 0x9ecae1, 0x74c476, 0xfff078, 0xfdae61, 0xf46d43, 0xd7191c, 0xae017e, 0x6a3d9a))
    val stp = bands(listOf(0.5, 1, 2, 3, 4, 6, 8, 10), listOf(0xc7e9c0, 0xfff078, 0xfdae61, 0xf46d43, 0xd7191c, 0xae017e, 0x6a3d9a, 0x3f007d))
    val relativeHumidity = bands(
        listOf(0, 2.5, 5, 7.5, 10, 12.5, 15, 17.5, 20, 22.5, 25, 27.5, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100),
        listOf(0x681813, 0x74231e, 0x983e3a, 0xbe5b58, 0xc39172, 0x9d745e, 0x765849, 0x503b34, 0x565656, 0x828282, 0xadadad, 0xd8d8d8,
            0xccfdb9, 0x8ec17f, 0x528644, 0x1e4b10, 0x6f9eab, 0x59858d, 0x436a6f, 0x2e5050, 0x666695, 0x545084, 0x433b73, 0x312662, 0x73476e, 0x7e5870))
    val precipitableWater = bands(listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 2.75, 3.0),
        listOf(0xaf9f83, 0x8f7d69, 0x6f5c4e, 0xdfeaac, 0xb8e8a1, 0x8ebd80, 0x64935f, 0x3c693e, 0x6f9eab, 0x5e8b94, 0x4f787e))
    val component = listOf(Stop(-40.0, 0x2166ac), Stop(-20.0, 0x67a9cf), Stop(-5.0, 0xd1e5f0), Stop(0.0, 0xf7f7f7), Stop(5.0, 0xfddbc7),
        Stop(20.0, 0xef8a62), Stop(40.0, 0xb2182b))

    fun rainbow(lo: Double, hi: Double): List<Stop> {
        val colours = listOf(0x6a3d9a, 0x3b4cc0, 0x00a0ff, 0x00c8a0, 0x60d040, 0xe6e61e, 0xffa000, 0xe03020, 0x8c0a0a)
        return colours.mapIndexed { i, c -> Stop(lo + (hi - lo) * i / 8.0, c) }
    }

    fun temperature(fahrenheit: Boolean): List<Stop> = listOf(-30 to 0xffffff, -10 to 0x9a64c8, 0 to 0x6496ff, 20 to 0x00b4ff, 32 to 0x00c864,
        50 to 0x96dc00, 70 to 0xffff00, 85 to 0xffa000, 100 to 0xff0000, 115 to 0x960000)
        .map { (v, c) -> Stop(if (fahrenheit) v.toDouble() else (v - 32.0) / 1.8, c) }

    fun cloud(colour: Int): List<Stop> {
        val r = colour shr 16 and 255; val g = colour shr 8 and 255; val b = colour and 255
        return (0..8).map {
            val t = it / 8.0
            Stop(10.0 + 90.0 * t, ((240 + (r - 240) * t).toInt() shl 16) or ((240 + (g - 240) * t).toInt() shl 8) or (240 + (b - 240) * t).toInt())
        }
    }

    /** SPC viewer's paintball colour per member, in bit order (member names in the store: HRRR, RRFS, REFS M01, ...). */
    val memberColours = mapOf("HRRR" to 0x4c4c4c, "RRFS" to 0x007399, "REFS M01" to 0x009985, "REFS M02" to 0x009947, "REFS M03" to 0x009909,
        "REFS M05" to 0x469900, "HRRR TL06" to 0x7f7f7f, "RRFS TL06" to 0x4cd3ff, "HRRR TL12" to 0xb3b3b3, "RRFS TL12" to 0xb3ecff)

    fun memberDisplayName(raw: String) = when (raw) {
        "RRFS" -> "RRFS Ctl"; "HRRR TL06" -> "HRRR -6h"; "HRRR TL12" -> "HRRR -12h"
        "RRFS TL06" -> "RRFS Ctl -6h"; "RRFS TL12" -> "RRFS Ctl -12h"; else -> raw
    }
}
