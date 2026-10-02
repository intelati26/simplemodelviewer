package simplemodelview

/**
 * The MRMS products the app offers, named as the AWS bucket (`noaa-mrms-pds/CONUS/<id>/`) names them. Each says how its stored values read
 * (units, the codes meaning "no coverage" / "no echo"), how cells combine when a wide area is decimated, how often a new file appears and
 * how it is colored. Surveyed from real files 2026-10-02 (docs/mrms-products.md); the decoder reads all of them (PNG packing, 8 to 32 bits).
 */
object MrmsCatalog {
    const val BUCKET = "https://noaa-mrms-pds.s3.amazonaws.com"
    const val REFLECTIVITY = "Reflectivity"
    const val TOPS = "Echo tops and VIL"
    const val ROTATION = "Rotation"
    const val HAIL = "Hail"
    const val LIGHTNING = "Lightning"
    const val PRECIP = "Rainfall"
    const val FLASH = "Flash flooding"
    const val QUALITY = "Quality"
    const val MODEL = "Model and heights"
    const val LEVELS = "3D mosaic"

    val groups = listOf(REFLECTIVITY, TOPS, ROTATION, HAIL, LIGHTNING, PRECIP, FLASH, QUALITY, MODEL, LEVELS)

    // ---- colors ----
    /** [count] colors spread evenly along the key colors (a color ramp for banded palettes). */
    internal fun ramp(count: Int, vararg keys: Int): List<Int> = (0 until count).map { i ->
        val t = if (count == 1) 0.0 else i.toDouble() / (count - 1) * (keys.size - 1)
        val k = minOf(t.toInt(), keys.size - 2)
        val f = t - k
        fun ch(shift: Int) = ((keys[k] shr shift and 255) * (1 - f) + (keys[k + 1] shr shift and 255) * f).toInt()
        (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun banded(thresholds: List<Number>, colors: List<Int>, units: String, digits: Int, factor: Double = 1.0, offset: Double = 0.0) =
        Style(Kind.Banded, bands(thresholds, colors), factor = factor, offset = offset, units = units, digits = digits)

    private val nwsReflectivity = listOf(0x04e9e7, 0x019ff4, 0x0300f4, 0x02fd02, 0x01c501, 0x008e00, 0xfdf802, 0xe5bc00, 0xfd9500, 0xfd0000,
        0xd40000, 0xbc0000, 0xf800fd, 0x9854c6, 0xfdfdfd)

    /** The NWS radar color table (5 dBZ steps); below 5 dBZ nothing is drawn. */
    private val reflectivity = banded((5..75 step 5).toList(), nwsReflectivity, "dBZ", 1)

    /** Azimuthal-shear tracks in 0.001 / s: values below 2 are noise and stay transparent. */
    private val rotation = banded(listOf(2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 30),
        listOf(0x9fd3ff, 0x4aa8ff, 0x2c6cf0, 0x25c25a, 0xa7e03a, 0xffe600, 0xffa000, 0xff4a00, 0xd01010, 0xc000c0, 0x5a2a8c), "x10^-3 /s", 0)

    /** Accumulations are stored in mm and shown in inches. */
    private val rainfall = Style(Kind.Banded, Palettes.qpf, factor = 1.0 / 25.4, units = "in", digits = 2)
    private val rainRate = Style(Kind.Banded, bands(listOf(0.01, 0.05, 0.1, 0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6), Palettes.qpf.map { it.rgb }),
        factor = 1.0 / 25.4, units = "in/hr", digits = 2)

    private const val KFT_PER_KM = 1.0 / 0.3048
    private val echoTopColors = listOf(0xb4e6ff, 0x82c8ff, 0x4fa3ff, 0x2d78e6, 0x1a9a3c, 0x6cc24a, 0xd4e62f, 0xffd400, 0xffa500, 0xff6a00,
        0xe62020, 0xb01010, 0xd020c0, 0x7a2aa8)
    private val echoTopThresholds = listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70)
    private val echoTopKft = banded(echoTopThresholds, echoTopColors, "kft", 1)                                  // stored in kft
    private val echoTopKm = banded(echoTopThresholds, echoTopColors, "kft", 1, factor = KFT_PER_KM)             // stored in km MSL
    private val vil = banded(listOf(1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70), nwsReflectivity, "kg/m2", 1)
    private val vii = banded(listOf(0.5, 1, 2, 3, 4, 5, 6, 8, 10, 12), ramp(10, 0xd6ecff, 0x3b8de0, 0x6a2c91), "kg/m2", 1)
    private val vilDensity = banded(listOf(0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4), ramp(8, 0xdff3d0, 0x4ab04a, 0xe0c020, 0xd02020), "g/m3", 1)
    private val thickness = Style(Kind.Blended, Palettes.rainbow(0.0, 12.0), units = "km", digits = 1, hideBelow = 0.05)

    private val hailInches = banded(listOf(0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3, 4, 5),
        listOf(0x9be39b, 0x4fc34f, 0x2e9e2e, 0xffe14a, 0xffb02e, 0xff7a1a, 0xff3b1a, 0xd1131a, 0xa00a3a, 0xc000c0, 0x7a1fa8, 0x4b1480), "in", 2, factor = 1.0 / 25.4)
    private val shi = banded(listOf(10, 25, 50, 75, 100, 150, 200, 300, 400, 500, 650, 800), ramp(12, 0x9be39b, 0xffe14a, 0xd1131a, 0x4b1480), "index", 0)
    private val probability = banded(listOf(10, 20, 30, 40, 50, 60, 70, 80, 90), Palettes.probability.map { it.rgb }, "%", 0)
    private val lightningDensity = banded(listOf(0.01, 0.025, 0.05, 0.1, 0.2, 0.4, 0.8, 1.6), ramp(8, 0xfff3a0, 0xffa500, 0xd02020, 0x7a1fa8), "fl/km2/min", 2)

    private val ffgRatio = banded(listOf(0.25, 0.5, 0.75, 1, 1.25, 1.5, 2, 3, 5, 10), ramp(10, 0x9be39b, 0xffe14a, 0xe03020, 0x7a1fa8), "ratio", 2)
    private val ari = banded(listOf(1, 2, 5, 10, 25, 50, 100, 200, 500, 1000), ramp(10, 0x9be39b, 0xffe14a, 0xe03020, 0x7a1fa8), "years", 0)
    private val soil = banded(listOf(20, 40, 50, 60, 70, 80, 90, 100), ramp(8, 0xe8d8b0, 0x6aa84f, 0x2c6cf0, 0x1a237e), "%", 0)
    private val streamflow = banded(listOf(1, 5, 10, 25, 50, 100, 250, 500, 1000, 5000, 10000), ramp(11, 0xbfe3ff, 0x2c6cf0, 0xe03020, 0x7a1fa8), "m3/s", 0)
    private val unitStreamflow = banded(listOf(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10), ramp(9, 0xbfe3ff, 0x2c6cf0, 0xe03020, 0x7a1fa8), "m3/s/km2", 2)

    /** Differential reflectivity: about 0 for light rain, higher for big drops, negative for hail / tumbling debris; below -2 dB is mostly noise. */
    private val zdr = banded(listOf(-2, -1, -0.5, 0, 0.5, 1, 1.5, 2, 3, 4, 6),
        listOf(0x1f3a93, 0x3f7fe0, 0x9cc7f2, 0xc8c8c8, 0xa8e08a, 0x4fc34f, 0xd4e62f, 0xffd400, 0xff8a00, 0xe03020, 0xc000c0), "dB", 1)

    /** Correlation coefficient: about 0.97-1 in rain, lower where the echoes are mixed (hail, melting snow, debris, non-weather). */
    private val rhohv = banded(listOf(0.5, 0.7, 0.8, 0.85, 0.9, 0.93, 0.96, 0.98, 1.0),
        listOf(0xc000c0, 0xe03020, 0xff8a00, 0xffd400, 0xd4e62f, 0x4fc34f, 0x00c8a0, 0x3f7fe0, 0x1f3a93), "", 2)

    /** The 33 heights (km above sea level) of the 3D mosaic: every 250 m to 3 km, every 500 m to 9 km, every km to 19 km. */
    val mosaicLevels: List<Double> = (0..10).map { 0.5 + it * 0.25 } + (0..11).map { 3.5 + it * 0.5 } + (10..19).map { it.toDouble() }

    private val quality = banded(listOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0), ramp(10, 0xd02020, 0xffe14a, 0x2e9e2e), "", 2)
    private val heightKft = Style(Kind.Blended, Palettes.rainbow(0.0, 20.0), factor = KFT_PER_KM / 1000.0, units = "kft", digits = 1, hideBelow = 0.05)
    private val temperature = Style(Kind.Blended, Palettes.temperature(true), factor = 1.8, offset = 32.0, units = "F", digits = 0)

    /** Surface precipitation type: the seven categories of NSSL's own legend (codes 96, 91, 10, 7, 6, 3, 1); 0 is "none" and stays transparent. */
    private val precipType = Style(
        Kind.Categorical,
        listOf(Stop(96.0, 0x009700), Stop(91.0, 0x00f900), Stop(10.0, 0x6effff), Stop(7.0, 0x960096), Stop(6.0, 0xff3232), Stop(3.0, 0xb0c4ff), Stop(1.0, 0x0450a4)),
        labels = listOf("Tropical convective", "Tropical stratiform", "Cool stratiform", "Hail", "Convective", "Snow", "Warm stratiform"),
        digits = 0,
    )

    // ---- the products ----
    // frames: how many of the newest files the slider covers
    private fun framesFor(cadence: Int) = when { cadence <= 5 -> 30; cadence <= 10 -> 18; cadence <= 15 -> 16; else -> 12 }

    private fun product(
        id: String, title: String, group: String, style: Style, cadence: Int = 2, pool: Pool = Pool.Max, missing: Double = -0.5, cell: Double = 0.01,
        quantity: Quantity = Quantity.None,
    ) = MrmsProduct(id, title, pool, missing, style, cadence, framesFor(cadence), cell, group, quantity)

    private val list = ArrayList<MrmsProduct>()
    private fun add(p: MrmsProduct) { list += p }

    private fun hours(h: Int) = if (h < 24) "$h h" else "${h} h"

    init {
        // reflectivity: -99 no echo, -999 no coverage
        fun refl(id: String, title: String, cadence: Int = 2) = add(product(id, title, REFLECTIVITY, reflectivity, cadence, missing = -90.0))
        refl("MergedReflectivityQCComposite_00.50", "Composite Reflectivity")
        refl("MergedReflectivityComposite_00.50", "Composite Reflectivity (raw, no QC)")
        refl("MergedBaseReflectivityQC_00.50", "Base Reflectivity (lowest tilt)")
        refl("MergedBaseReflectivity_00.50", "Base Reflectivity (raw)")
        refl("SeamlessHSR_00.00", "Seamless Hybrid Scan Reflectivity")
        refl("ReflectivityAtLowestAltitude_00.50", "Reflectivity at Lowest Altitude")
        refl("MergedReflectivityAtLowestAltitude_00.50", "Reflectivity at Lowest Altitude (raw)")
        refl("LowLevelCompositeReflectivity_00.50", "Low-Level Composite Reflectivity (0-4 km)")
        refl("LayerCompositeReflectivity_Low_00.50", "Layer Reflectivity, low (0-24 kft)")
        refl("LayerCompositeReflectivity_High_00.50", "Layer Reflectivity, high (24-60 kft)")
        refl("LayerCompositeReflectivity_Super_00.50", "Layer Reflectivity, super high (33-60 kft)")
        refl("CREF_1HR_MAX_00.50", "Composite Reflectivity, 1 h maximum", 60)
        refl("BREF_1HR_MAX_00.50", "Base Reflectivity, 1 h maximum", 60)
        for (c in listOf(0, -5, -10, -15, -20)) refl("Reflectivity_${c}C_00.50", "Reflectivity at $c C")

        // echo tops and VIL: -3 no coverage, -1 no echo
        for (dbz in listOf(18, 30, 50, 60)) add(product("EchoTop_${dbz}_00.50", "Echo Top, $dbz dBZ", TOPS, echoTopKm))
        add(product("LVL3_HREET_00.50", "Enhanced Echo Top (high resolution)", TOPS, echoTopKft))
        add(product("VIL_00.50", "Vertically Integrated Liquid", TOPS, vil))
        add(product("LVL3_HighResVIL_00.50", "Vertically Integrated Liquid (high resolution)", TOPS, vil))
        add(product("VIL_Density_00.50", "VIL Density", TOPS, vilDensity))
        add(product("VII_00.50", "Vertically Integrated Ice", TOPS, vii))
        add(product("VIL_Max_120min_00.50", "VIL Swath, 2 h", TOPS, vil, 30))
        add(product("VIL_Max_1440min_00.50", "VIL Swath, 24 h", TOPS, vil, 30))
        for ((id, title) in listOf("H50_Above_0C" to "50 dBZ top above 0 C", "H50_Above_-20C" to "50 dBZ top above -20 C",
            "H60_Above_0C" to "60 dBZ top above 0 C", "H60_Above_-20C" to "60 dBZ top above -20 C"))
            add(product("${id}_00.50", "Thickness: $title", TOPS, thickness, missing = -500.0))

        // rotation (0.005 degree grids)
        add(product("MergedAzShear_0-2kmAGL_00.50", "Azimuthal Shear (0-2 km), now", ROTATION, rotation, cell = 0.005))
        add(product("MergedAzShear_3-6kmAGL_00.50", "Azimuthal Shear (3-6 km), now", ROTATION, rotation, cell = 0.005))
        for ((prefix, layer) in listOf("RotationTrack" to "0-2 km", "RotationTrackML" to "3-6 km")) {
            for (m in listOf(30, 60, 120, 240, 360, 1440)) {
                val span = if (m < 60) "$m min" else hours(m / 60)
                add(product("${prefix}${m}min_00.50", "Rotation Track, $span ($layer)", ROTATION, rotation, if (m <= 60) 2 else 30, cell = 0.005))
            }
        }

        // hail
        add(product("MESH_00.50", "Hail Size (MESH)", HAIL, hailInches))
        for (m in listOf(30, 60, 120, 240, 360, 1440))
            add(product("MESH_Max_${m}min_00.50", "Hail Swath, ${if (m < 60) "$m min" else hours(m / 60)}", HAIL, hailInches, if (m <= 60) 2 else 30))
        add(product("POSH_00.50", "Probability of Severe Hail", HAIL, probability))
        add(product("SHI_00.50", "Severe Hail Index", HAIL, shi))

        // lightning (NLDN)
        for (m in listOf(1, 5, 15, 30))
            add(product("NLDN_CG_${m.toString().padStart(3, '0')}min_AvgDensity_00.00", "Cloud-to-Ground Lightning, $m min", LIGHTNING, lightningDensity, 1))
        for (m in listOf(30, 60))
            add(product("LightningProbabilityNext${m}minGrid_scale_1", "Lightning Probability, next $m min", LIGHTNING, probability, 2, missing = -50.0))

        // rainfall
        add(product("PrecipFlag_00.00", "Precipitation Type", PRECIP, precipType, 2, Pool.Mode))
        add(product("PrecipRate_00.00", "Precipitation Rate", PRECIP, rainRate, 2, Pool.Mean))
        add(product("RadarOnly_QPE_15M_00.00", "15 min Rainfall (radar only)", PRECIP, rainfall, 15, Pool.Mean))
        add(product("RadarOnly_QPE_01H_00.00", "1 h Rainfall (radar only)", PRECIP, rainfall, 2, Pool.Mean))
        for (h in listOf(3, 6, 12, 24, 48, 72)) add(product("RadarOnly_QPE_${h.toString().padStart(2, '0')}H_00.00", "$h h Rainfall (radar only)", PRECIP, rainfall, 60, Pool.Mean))
        add(product("RadarOnly_QPE_Since12Z_00.00", "Rainfall since 12Z (radar only)", PRECIP, rainfall, 60, Pool.Mean))
        for (h in listOf(1, 3, 6, 12, 24, 48, 72)) {
            val d = h.toString().padStart(2, '0')
            add(product("MultiSensor_QPE_${d}H_Pass2_00.00", "$h h Rainfall (multi-sensor, best)", PRECIP, rainfall, 60, Pool.Mean))
            add(product("MultiSensor_QPE_${d}H_Pass1_00.00", "$h h Rainfall (multi-sensor, quicker)", PRECIP, rainfall, 60, Pool.Mean))
        }

        // flash flooding (FLASH): -999 / -9999 no coverage
        for (h in listOf("01H", "03H", "06H")) add(product("FLASH_QPE_FFG${h}_00.00", "Rainfall / flash-flood guidance, ${h.removeSuffix("H").trimStart('0')} h", FLASH, ffgRatio, 2, missing = -500.0))
        add(product("FLASH_QPE_FFGMAX_00.00", "Rainfall / flash-flood guidance, maximum", FLASH, ffgRatio, 2, missing = -500.0))
        for (h in listOf("30M" to "30 min", "01H" to "1 h", "03H" to "3 h", "06H" to "6 h", "12H" to "12 h", "24H" to "24 h", "MAX" to "maximum"))
            add(product("FLASH_QPE_ARI${h.first}_00.00".let { if (h.first == "MAX") "FLASH_QPE_ARIMAX_00.00" else it }, "Rainfall return period, ${h.second}", FLASH, ari, 2, missing = -500.0))
        for ((model, name) in listOf("CREST" to "CREST", "SAC" to "SAC-SMA", "HP" to "hydrophobic")) {
            add(product("FLASH_${model}_MAXSTREAMFLOW_00.00", "Streamflow ($name)", FLASH, streamflow, 10, missing = -500.0))
            add(product("FLASH_${model}_MAXUNITSTREAMFLOW_00.00", "Unit streamflow ($name)", FLASH, unitStreamflow, 10, missing = -500.0))
            if (model != "HP") add(product("FLASH_${model}_MAXSOILSAT_00.00", "Soil saturation ($name)", FLASH, soil, 10, missing = -500.0))
        }

        // quality
        add(product("RadarQualityIndex_00.00", "Radar Quality Index", QUALITY, quality, 2, Pool.Mean))
        for (h in listOf(1, 3, 6, 12, 24, 48, 72))
            add(product("RadarAccumulationQualityIndex_${h.toString().padStart(2, '0')}H_00.00", "$h h rainfall radar quality", QUALITY, quality, 60, Pool.Mean))
        for (h in listOf(1, 3, 6, 12, 24, 48, 72)) {
            val d = h.toString().padStart(2, '0')
            add(product("GaugeInflIndex_${d}H_Pass2_00.00", "$h h gauge influence (best)", QUALITY, quality, 60, Pool.Mean, missing = -0.0005))
            add(product("GaugeInflIndex_${d}H_Pass1_00.00", "$h h gauge influence (quicker)", QUALITY, quality, 60, Pool.Mean, missing = -0.0005))
        }

        // the 3D mosaic: one entry per field, the height chosen on the page (33 server directories each, see mosaicLevels)
        val levelIndex = mosaicLevels.indexOf(3.0)
        add(product("MergedReflectivityQC", "3D Reflectivity (by height)", LEVELS, reflectivity, 2, missing = -90.0).copy(levels = mosaicLevels, level = levelIndex))
        add(product("MergedZdr", "3D Differential Reflectivity, ZDR (by height)", LEVELS, zdr, 5, missing = -90.0).copy(levels = mosaicLevels, level = levelIndex))
        add(product("MergedRhoHV", "3D Correlation Coefficient, RhoHV (by height)", LEVELS, rhohv, 5, Pool.Mean, missing = -90.0).copy(levels = mosaicLevels, level = levelIndex))

        // model-derived fields and heights
        add(product("Model_0degC_Height_00.50", "Freezing level height", MODEL, heightKft, 60, Pool.Mean))
        add(product("Model_SurfaceTemp_00.50", "Surface temperature (model)", MODEL, temperature, 60, Pool.Mean, missing = -500.0))
        add(product("Model_WetBulbTemp_00.50", "Wet-bulb temperature (model)", MODEL, temperature, 60, Pool.Mean, missing = -500.0))
        add(product("WarmRainProbability_00.50", "Probability of warm rain", MODEL, probability, 60, Pool.Mean))
        add(product("BrightBandTopHeight_00.00", "Bright band top height", MODEL, heightKft))
        add(product("BrightBandBottomHeight_00.00", "Bright band bottom height", MODEL, heightKft))
        add(product("HeightCompositeReflectivity_00.50", "Height of composite reflectivity", MODEL, heightKft))
        add(product("HeightLowLevelCompositeReflectivity_00.50", "Height of low-level composite reflectivity", MODEL, heightKft))
    }

    // which products measure a length / height / temperature (so their units can be switched)
    private fun quantityOf(p: MrmsProduct) = when (p.style) {
        rainfall, rainRate, hailInches -> Quantity.Length
        echoTopKm, echoTopKft, heightKft -> Quantity.Height
        temperature -> Quantity.Temperature
        else -> Quantity.None
    }

    val all: List<MrmsProduct> = list.map { it.copy(quantity = quantityOf(it)) }

    val default = all.first()

    fun byId(id: String?) = all.firstOrNull { it.id == id }
}
