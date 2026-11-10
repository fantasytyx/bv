package dev.aaa1115910.bv.entity.carddata

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoCardDataTest {
    @Test
    fun historyBadgeAppearsInCoverBadges() {
        val card = videoCardData(
            historyBusiness = "archive",
            historyBadgeText = "互动视频"
        )

        assertEquals(listOf("互动视频"), card.coverBadges)
    }

    @Test
    fun historyBadgeAppearsWithoutHistoryBusiness() {
        val card = videoCardData(historyBadgeText = "笔记")

        assertEquals(listOf("笔记"), card.coverBadges)
    }

    @Test
    fun blankHistoryBadgeIsOmitted() {
        val card = videoCardData(historyBadgeText = " ")

        assertEquals(emptyList(), card.coverBadges)
    }

    @Test
    fun chargingBadgeBehaviorIsUnchanged() {
        val card = videoCardData(
            isChargingArc = true,
            badgeText = "专属"
        )

        assertEquals(listOf("⚡专属"), card.coverBadges)
    }

    @Test
    fun pgcBadgeBehaviorIsUnchanged() {
        val card = videoCardData(pgcTypeName = "番剧")

        assertEquals(listOf("番剧"), card.coverBadges)
    }

    private fun videoCardData(
        historyBusiness: String? = null,
        historyBadgeText: String = "",
        isChargingArc: Boolean = false,
        badgeText: String = "",
        pgcTypeName: String? = null
    ) = VideoCardData(
        avid = 1,
        title = "title",
        cover = "cover",
        upName = "author",
        historyBusiness = historyBusiness,
        historyBadgeText = historyBadgeText,
        isChargingArc = isChargingArc,
        badgeText = badgeText,
        pgcTypeName = pgcTypeName
    )
}
