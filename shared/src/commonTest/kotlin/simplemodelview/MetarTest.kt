package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetarTest {
    private val csv = """raw_text,station_id,observation_time,latitude,longitude,temp_c,dewpoint_c,wind_dir_degrees,wind_speed_kt,wind_gust_kt,visibility_statute_mi,altim_in_hg,sea_level_pressure_mb,corrected,auto,auto_station,maintenance_indicator_on,no_signal,lightning_sensor_off,freezing_rain_sensor_off,present_weather_sensor_off,wx_string,sky_cover,cloud_base_ft_agl,sky_cover,cloud_base_ft_agl,sky_cover,cloud_base_ft_agl,sky_cover,cloud_base_ft_agl,flight_category,three_hr_pressure_tendency_mb,maxT_c,minT_c,maxT24hr_c,minT24hr_c,precip_in,pcp3hr_in,pcp6hr_in,pcp24hr_in,snow_in,vert_vis_ft,metar_type,elevation_m
"METAR KBZN 181056Z AUTO 00000KT 4SM -SN BR FEW025 OVC036 00/M01 A2988 RMK AO2 SLP130 P0000 T00001006",KBZN,2026-11-18T10:56:00.000Z,45.7876,-111.1615,0,-0.6,0,0,,4,29.88,1013,,TRUE,TRUE,,,,,,-SN BR,FEW,2500,OVC,3600,,,,,MVFR,,,,,,0.005,,,,,,METAR,1349
"METAR KOKC 181052Z 16007KT 10SM FEW250 05/02 A3025 RMK AO2 SLP245 T00500017",KOKC,2026-11-18T10:52:00.000Z,35.3843,-97.6003,5,1.7,160,7,,10+,30.25,1024.5,,,TRUE,,,,,,,FEW,25000,,,,,,,VFR,,,,,,,,,,,,METAR,391"""

    private val json = """[{"icaoId": "KOKC", "receiptTime": "2026-11-18T10:56:23.695Z", "obsTime": 1794999120, "reportTime": "2026-11-18T11:00:00.000Z", "temp": 5, "dewp": 1.7, "wdir": 160, "wspd": 7, "visib": "10+", "altim": 1024.5, "slp": 1024.5, "qcField": 4, "metarType": "METAR", "rawOb": "METAR KOKC 181052Z 16007KT 10SM FEW250 05/02 A3025 RMK AO2 SLP245 T00500017", "lat": 35.3843, "lon": -97.6003, "elev": 391, "name": "Oklahoma City/Will Rogers Intl, OK, US", "cover": "FEW", "clouds": [{"cover": "FEW", "base": 25000}], "fltCat": "VFR"}, {"icaoId": "KOKC", "receiptTime": "2026-11-18T09:56:51.843Z", "obsTime": 1794995520, "reportTime": "2026-11-18T10:00:00.000Z", "temp": 5, "dewp": 2.2, "wdir": 150, "wspd": 9, "visib": "10+", "altim": 1023.8, "slp": 1023.8, "qcField": 4, "metarType": "METAR", "rawOb": "METAR KOKC 180952Z 15009KT 10SM BKN250 05/02 A3023 RMK AO2 SLP238 T00500022", "lat": 35.3843, "lon": -97.6003, "elev": 391, "name": "Oklahoma City/Will Rogers Intl, OK, US", "cover": "BKN", "clouds": [{"cover": "BKN", "base": 25000}], "fltCat": "VFR"}]"""

    @Test
    fun bulkCsv() {
        val list = parseMetarCsv(csv)
        assertEquals(listOf("KBZN", "KOKC"), list.map { it.id })
        val okc = list[1]
        assertEquals(5.0, okc.tempC); assertEquals(1.7, okc.dewC); assertEquals(160, okc.windDir); assertEquals(7, okc.windKt)
        assertEquals("10+", okc.visibility); assertEquals("VFR", okc.category)
        assertEquals(epochOf(2026, 11, 18, 10, 52, 0), okc.time)
        assertTrue(okc.raw.startsWith("METAR KOKC"))
        val bzn = list[0]
        assertEquals("-SN BR", bzn.weather); assertEquals(null, bzn.windDir, "calm"); assertEquals(0, bzn.windKt)
        assertEquals("41", metarTemp(okc.tempC, false)); assertEquals("5", metarTemp(okc.tempC, true))
    }

    @Test
    fun historyJson() {
        val list = parseMetarJson(json)
        assertEquals(2, list.size)
        assertEquals(epochOf(2026, 11, 18, 10, 52, 0), list[0].time, "the observation time, not the report hour")
        assertEquals(150, list[1].windDir)
    }

    @Test
    fun csvQuotes() {
        assertEquals(listOf("a,b", "c", "", "d\"e"), csvFields("\"a,b\",c,,\"d\"\"e\""))
    }

    @Test
    fun fiveMinute() {
        val text = "station,valid,tmpf,dwpf,sknt,drct,gust\nOKC,2026-11-17 12:00,M,M,3.00,180.00,M\nOKC,2026-11-17 12:52,34.00,33.00,4.00,150.00,M\n"
        val rows = parseFiveMinute(text)
        assertEquals(2, rows.size)
        assertEquals(null, rows[0].tempF); assertEquals(34.0, rows[1].tempF); assertEquals(3.0, rows[0].windKt)
        assertEquals(epochOf(2026, 11, 17, 12, 52, 0), rows[1].time)
        assertTrue(MetarFeed.fiveMinuteUrl("KOKC", rows[0].time, rows[1].time).contains("station=OKC&"))
    }

    @Test
    fun thinningKeepsTheFirstAndDropsNeighbors() {
        val xy = floatArrayOf(0f, 0f, 10f, 0f, 100f, 0f, 105f, 5f, 300f, 300f)
        assertEquals(listOf(0, 2, 4), thinStations(xy, 50f))
    }

    private class OneHttp(val body: String) : Http {
        override suspend fun get(url: String) = HttpResult(200, body.encodeToByteArray())
    }

    @Test
    fun theReportInForceAtAFrame() = runTest {
        val repo = MetarRepository(OneHttp(csv)) { it }
        val now = epochOf(2026, 11, 18, 11, 0, 0)
        assertTrue(repo.refresh(now))
        assertEquals(listOf("KOKC"), repo.at(now, -100.0, 30.0, -95.0, 40.0).map { it.id })
        assertEquals(emptyList(), repo.at(epochOf(2026, 11, 18, 10, 30, 0), -100.0, 30.0, -95.0, 40.0), "the 10:52 report is not in force at 10:30")
        assertEquals(emptyList(), repo.at(now + 3 * 3600, -100.0, 30.0, -95.0, 40.0), "too old by then")
    }
}
