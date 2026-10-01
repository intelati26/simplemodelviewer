package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals

class HttpTest {
    private val base = "simplemodelview/0.1 (unofficial viewer; +https://github.com/intelati26/simplemodelviewer;)"

    @Test fun emailValidation() {
        for (ok in listOf("me@example.com", " a.b+c@sub.example.org ", "x@y.zz")) assertEquals(true, isValidEmail(ok), ok)
        for (bad in listOf("", "me", "me@", "@example.com", "me@example", "me@example.", "me@@example.com", "me @example.com", "m\u00e9@example.com"))
            assertEquals(false, isValidEmail(bad), bad)
    }

    @Test fun noContactLeavesTheBase() {
        assertEquals(base, userAgent(base, ""))
        assertEquals(base, userAgent(base, "   "))
    }

    @Test fun contactIsAppended() = assertEquals("$base contact: me@example.com", userAgent(base, " me@example.com "))

    @Test fun nonAsciiAndHeaderBreakersAreDropped() {
        assertEquals("$base contact: mecaf@example.com", userAgent(base, "me\u00e9caf@exa mple.com\r\n"))
        assertEquals("$base contact: ab", userAgent(base, "a(b);"))
    }

    @Test fun contactIsCapped() = assertEquals(100, userAgent("", "x".repeat(500)).removePrefix(" contact: ").length)
}
