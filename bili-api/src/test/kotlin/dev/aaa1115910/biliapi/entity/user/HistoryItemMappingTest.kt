package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.history.HistoryItem as HttpHistoryItem
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryItemMappingTest {
    @Test
    fun httpHistoryBadgeIsPreservedByMapping() {
        val mapped = HistoryItem.fromHistoryItem(
            HttpHistoryItem(
                title = "title",
                longTitle = "",
                cover = "cover",
                uri = "",
                history = HttpHistoryItem.HistoryInfo(
                    oid = 1,
                    epid = 0,
                    bvid = "BV1xx411c7mD",
                    page = 1,
                    cid = 2,
                    part = "",
                    business = "archive",
                    dt = 1
                ),
                videos = 1,
                authorName = "author",
                authorFace = "",
                authorMid = 3,
                viewAt = 4,
                progress = 5,
                badge = "互动视频",
                showTitle = "",
                duration = 60,
                current = "",
                total = 1,
                newDesc = "",
                isFinish = 0,
                isFav = 0,
                kid = 1,
                tagName = "",
                liveStatus = 0
            )
        )

        assertEquals("互动视频", mapped.badge)
    }
}
