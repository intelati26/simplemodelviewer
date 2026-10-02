package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals

/** The buckets mirror SPC's REFS viewer menu: Guidance, Synoptic, Severe, Winter, Fire, Precipitation, Storm Attributes, Member Viewer. */
class GroupsTest {
    private fun g(id: String, members: Boolean = false) = Groups.of(id, members)

    @Test fun orderMatchesTheSite() = assertEquals(
        listOf("Guidance", "Synoptic", "Severe", "Winter", "Fire", "Precipitation", "Storm Attributes", "Member Viewer", "Other fields"), Groups.ordered,
    )

    @Test fun guidanceIsGrefs() {
        assertEquals(Groups.GUIDANCE, g("grefs-4hr/tor_prob"))
        assertEquals(Groups.GUIDANCE, g("grefs-24hr/hail_prob"))
        assertEquals(Groups.GUIDANCE, g("refsct-1hr/thunder_prob"))
    }

    @Test fun synopticSevereWinterFirePrecip() {
        for (id in listOf("z500_mean", "t2m_mean", "td2m_mean", "pmsl_mean", "cld_low_mean", "pwat_mean", "pwat_prob_t>0p500_t<0p800")) assertEquals(Groups.SYNOPTIC, g(id), id)
        for (id in listOf("sbcape_mean", "sbcape_prob_t>500", "mucape_mean", "sbcinh_mean", "srh01_mean", "srh03_mean", "shrmag06_mean", "stp_fixed_mean", "stp_fixed_prob_t>001")) assertEquals(Groups.SEVERE, g(id), id)
        for (id in listOf("snow_01h_acc_mean", "snow_12h_acc_pmmean", "frzr_qpf_03h_acc_mean", "frzr_fram_24h_acc_overlap12h_mean")) assertEquals(Groups.WINTER, g(id), id)
        for (id in listOf("fosberg_mean", "rh2m_min", "gust_max", "s10m_01h_max_mean", "rh2m_s10m_prob_t<020_t>015")) assertEquals(Groups.FIRE, g(id), id)
        for (id in listOf("qpf_01h_acc_mean", "qpf_06h_acc_prob_t>001", "qpf_24h_acc_overlap12h_max")) assertEquals(Groups.PRECIP, g(id), id)
    }

    @Test fun stormAttributes() {
        for (id in listOf("cref_max", "cref_pb_t040", "maxref1km_04h_max_overlap_max", "uh25_04h_max_overlap_max", "uh25min_24h_min_overlap12h_min",
            "wmax_04h_max_overlap_pb_t020", "s10m_04h_max_overlap_max", "s10m_24h_max_overlap12h_pb_t030")) assertEquals(Groups.STORM, g(id), id)
    }

    @Test fun singleMemberFieldsAreTheMemberViewer() {
        assertEquals(Groups.MEMBERS, g("cref", members = true))
        assertEquals(Groups.MEMBERS, g("uh25_04h_max_overlap", members = true))
    }

    @Test fun unknownIdsLandInOther() = assertEquals(Groups.OTHER, g("something_new"))

    @Test fun everyCuratedTitleHasABucketOtherThanOther() {
        // the viewer's own product titles all belong to one of the site's menus
        val stray = curatedList.map { it.first }.filter { g(it) == Groups.OTHER }
        assertEquals(emptyList(), stray)
    }
}
