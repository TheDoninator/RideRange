package com.elect.riderange.rules

import com.elect.riderange.Fixtures
import com.elect.riderange.testing.assertEquals
import com.elect.riderange.testing.assertNotNull
import com.elect.riderange.testing.assertNull
import com.elect.riderange.testing.assertTrue
import kotlin.test.Test

/** Checks the bundled dataset (src/main/assets/regulations.json) and the lookups. */
class RegulationsTest {
    private val regs = Regulations.parse(Fixtures.main("assets/regulations.json"))

    private val allCodes = listOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA",
        "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR",
        "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
    )

    @Test
    fun coversAll50StatesAndDc() {
        assertEquals(51, allCodes.size)
        for (c in allCodes) {
            val s = regs.state(c)
            assertNotNull("missing $c", s)
            s!!
            assertTrue("$c summary", s.summary.isNotBlank())
            assertTrue("$c scooter rules", s.scooter.items.isNotEmpty())
            assertTrue("$c e-bike rules", s.ebike.items.isNotEmpty())
            assertTrue("$c sources", s.sources.isNotEmpty() && s.sources.all { it.url.startsWith("https://") })
        }
        assertEquals(51, regs.states.size)
    }

    @Test
    fun utahIsThorough() {
        val ut = regs.state("UT")!!
        assertEquals("verified", ut.confidence)
        for (topic in listOf("Where to ride", "Sidewalks", "Max speed", "Age", "Helmet", "Licence & registration", "Lights", "Parking")) {
            assertNotNull("Utah scooter $topic", ut.scooter[topic])
        }
        assertTrue(ut.scooter["Max speed"]!!.contains("15 mph"))
        assertTrue(ut.scooter["Helmet"]!!.contains("21"))
        assertTrue(ut.ebike["Classes"]!!.contains("28 mph"))
        assertTrue(ut.sources.count { it.url.contains("le.utah.gov") } >= 5)
    }

    @Test
    fun cityLookup() {
        assertEquals("St. George", regs.city("UT", "Saint George")?.city)
        assertEquals("St. George", regs.city("ut", "st george")?.city)
        for (c in listOf("St. George", "Cedar City", "Hurricane", "Washington", "Salt Lake City", "Provo", "Ogden", "Logan")) {
            assertNotNull("Utah city $c", regs.city("UT", c))
        }
        assertNull(regs.city("NV", "St. George"))
        val l = regs.lookup("UT", "St. George")
        assertEquals("Utah", l.state?.name)
        assertNotNull(l.city)
        assertEquals("Utah", regs.state("utah")?.name)
        assertTrue(regs.citiesIn("UT").size >= 8)
    }

    @Test
    fun normalisesNames() {
        assertEquals(Regulations.norm("St. George"), Regulations.norm("Saint George"))
        assertEquals("saltlakecity", Regulations.norm("Salt Lake City"))
    }
}
