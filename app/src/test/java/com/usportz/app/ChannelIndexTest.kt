package com.usportz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelIndexTest {
    private data class Channel(val name: String, val group: String)

    @Test
    fun exactNameRanksAheadOfPartialMatches() {
        val index = ChannelIndex(
            listOf(
                Channel("ESPN 2", "US Sports"),
                Channel("ESPN", "Sports"),
                Channel("ESPN Deportes", "International")
            ),
            name = { it.name },
            group = { it.group }
        )

        assertEquals("ESPN", index.search("espn").first().name)
    }

    @Test
    fun searchReturnsOnlyRequestedNumberOfBestMatches() {
        val channels = (1..5000).map { Channel("ESPN Extra $it", "Sports") } +
            Channel("ESPN", "Sports")
        val index = ChannelIndex(channels, name = { it.name }, group = { it.group })

        val results = index.search("espn", limit = 3)

        assertEquals(3, results.size)
        assertEquals("ESPN", results.first().name)
    }

    @Test
    fun blankAndUnmatchedQueriesReturnNoResults() {
        val index = ChannelIndex(
            listOf(Channel("TSN 1", "Canada Sports")),
            name = { it.name },
            group = { it.group }
        )

        assertTrue(index.search("   ").isEmpty())
        assertTrue(index.search("baseball").isEmpty())
    }
}
