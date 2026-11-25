package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpcDiscussionTest {
    private val sample = """

509 
ACUS01 KWNS 251949
SWODY1
SPC AC 251947

Day 1 Convective Outlook  
NWS Storm Prediction Center Norman OK
0147 PM CST Wed Nov 25 2026

Valid 252000Z - 261200Z

...THERE IS A MARGINAL RISK OF SEVERE THUNDERSTORMS ACROSS PARTS OF
SOUTH FLORIDA AND THE KEYS AND OVER PORTIONS OF THE COASTAL
CAROLINAS...

...SUMMARY...
An isolated severe threat may develop over south Florida and the
Keys through this evening.

...20z Update...
Latest observational data continues to depict a baroclinic zone.

Northeasterly low-level flow limits buoyancy.

..Smith/Jones.. 11/25/2026

${'$'}${'$'}
"""

    @Test fun theHeaderAndHeadlineAreRead() {
        val d = assertNotNull(SpcDiscussionText.parse(sample))
        assertEquals("Day 1 Convective Outlook", d.title)
        assertEquals("0147 PM CST Wed Nov 25 2026", d.issued)
        assertEquals("Valid 252000Z - 261200Z", d.valid)
        assertTrue(d.headline!!.startsWith("There is a marginal risk of severe thunderstorms across parts of South Florida and the Keys"), d.headline)
        assertEquals("..Smith/Jones.. 11/25/2026", d.forecaster)
    }

    @Test fun sectionsKeepTheirParagraphsWithTheLineBreaksJoined() {
        val d = assertNotNull(SpcDiscussionText.parse(sample))
        assertEquals(listOf("Summary", "20z Update"), d.sections.map { it.title })
        assertEquals("An isolated severe threat may develop over south Florida and the Keys through this evening.", d.sections[0].paragraphs.single())
        assertEquals(2, d.sections[1].paragraphs.size)
    }

    @Test fun aProductWithoutAHeadlineStartsAtItsFirstSection() {
        val d = assertNotNull(SpcDiscussionText.parse("ACUS48 KWNS 250902\nSWOD48\n\nDay 4-8 Convective Outlook\nNWS Storm Prediction Center Norman OK\n0300 AM CST Wed Nov 25 2026\n\nValid 281200Z - 031200Z\n\n...DISCUSSION...\nModels indicate an upper trough.\n\nFor the next days.\n"))
        assertNull(d.headline)
        assertEquals("Discussion", d.sections.single().title)
        assertEquals(2, d.sections.single().paragraphs.size)
    }

    @Test fun somethingThatIsNotAnOutlookIsNull() {
        assertNull(SpcDiscussionText.parse("")); assertNull(SpcDiscussionText.parse("<html>Not found</html>"))
    }

    @Test fun theDaysMapToProducts() {
        assertEquals("SWODY2", SpcDiscussionText.pil(2)); assertEquals("SWOD48", SpcDiscussionText.pil(6))
    }
}
