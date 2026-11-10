package dev.aaa1115910.biliapi.http.entity.season

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebFollowingSeasonTest {
    @Test
    fun `section without episode ids can be decoded`() {
        val section = Json.decodeFromString<WebFollowingSeason.Section>(
            """
            {
              "copyright": "",
              "limit_group": 0,
              "season_id": 1,
              "section_id": 1,
              "watch_platform": 0
            }
            """.trimIndent()
        )

        assertTrue(section.episodeIds.isEmpty())
    }
}
