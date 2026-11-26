package simplemodelview

/**
 * The short product lists of Simple mode: what a person who watches the SPC outlook on TV most likely wants to open, about a dozen each. The picker shows only these
 * (and the starred ones, and the one on screen) until "All products" is asked for; searching always looks through everything.
 */
object SimpleProducts {
    /** SPC REFS (the model ensemble): storms, rotation, wind, instability, rain and snow, temperature and gusts. Ids are the zarr array names. */
    val refs: Set<String> = linkedSetOf(
        "cref", "cref_nmep_r013_t040", "maxref1km_04h_max_overlap_max",
        "uh25_04h_max_overlap_max", "uh25_04h_max_overlap_nmep_r013_t>99.85p",
        "s10m_04h_max_overlap_nmep_r013_t030", "gust_mean",
        "sbcape_mean", "stp_fixed_mean",
        "qpf_06h_acc_mean", "snow_12h_acc_mean", "t2m_mean",
    )

    /** MRMS (the radar mosaic): the picture now, storm height and strength, rotation, hail, lightning and rainfall. Ids are the server directory names. */
    val mrms: Set<String> = linkedSetOf(
        "SeamlessHSR_00.00", "MergedReflectivityQCComposite_00.50",
        "EchoTop_18_00.50", "VIL_00.50",
        "RotationTrack60min_00.50", "RotationTrack1440min_00.50",
        "MESH_00.50", "MESH_Max_1440min_00.50",
        "NLDN_CG_015min_AvgDensity_00.00",
        "MultiSensor_QPE_01H_Pass2_00.00", "MultiSensor_QPE_24H_Pass2_00.00", "RainTotal_3D",
    )
}
