package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsBackupTest {
    private val full = SettingsBackup(
        wifiOnlyLoop = true, dataSaver = true, cacheMegabytes = 300, contactEmail = "me@example.com",
        favourites = listOf("cref_max", "grefs-4hr/tor_prob"), places = listOf(BackupPlace("Norman, OK", 35.22, -97.44)),
        product = "uh25_04h_max_overlap_max", sector = "South Central",
    )

    @Test fun roundTrip() {
        val text = SettingsBackup.encode(full)
        assertTrue("\"app\": \"simplemodelview\"" in text && "\"format\": 1" in text)
        assertEquals(full, SettingsBackup.decode(text))
    }

    @Test fun defaultsRoundTripToo() = assertEquals(SettingsBackup(), SettingsBackup.decode(SettingsBackup.encode(SettingsBackup())))

    @Test fun notABackupIsRefused() {
        assertNull(SettingsBackup.decode("not json"))
        assertNull(SettingsBackup.decode("{}".replace("{}", "{\"app\":\"other\",\"format\":1}")))
        assertNull(SettingsBackup.decode("{\"app\":\"simplemodelview\",\"format\":2}"))      // from a newer version
        assertNull(SettingsBackup.decode("{\"app\":\"simplemodelview\",\"format\":0}"))
    }

    @Test fun unknownKeysAreIgnoredAndMissingOnesDefault() {
        val b = assertNotNull(SettingsBackup.decode("{\"app\":\"simplemodelview\",\"format\":1,\"future\":true,\"dataSaver\":true}"))
        assertEquals(true, b.dataSaver)
        assertEquals(150, b.cacheMegabytes)
    }

    @Test fun valuesAreCleanedNotTrusted() {
        val dirty = SettingsBackup(
            cacheMegabytes = 5_000_000, contactEmail = "not an email", favourites = listOf("a", "a", "", "b"),
            places = listOf(BackupPlace("ok", 10.0, 20.0), BackupPlace("off globe", 95.0, 0.0), BackupPlace("", 1.0, 1.0), BackupPlace("ok", 1.0, 1.0)),
            sector = "Mordor", product = "x".repeat(500),
        )
        val clean = assertNotNull(SettingsBackup.decode(SettingsBackup.encode(dirty)))
        assertEquals(2000, clean.cacheMegabytes)
        assertEquals("", clean.contactEmail)
        assertEquals(listOf("a", "b"), clean.favourites)
        assertEquals(listOf("ok"), clean.places.map { it.name })
        assertEquals(10.0, clean.places.single().lat)       // the first "ok" wins
        assertNull(clean.sector)
        assertNull(clean.product)
    }
}
