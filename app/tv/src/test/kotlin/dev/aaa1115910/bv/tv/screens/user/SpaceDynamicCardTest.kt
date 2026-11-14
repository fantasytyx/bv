package dev.aaa1115910.bv.tv.screens.user

import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.DynamicMajorType
import dev.aaa1115910.biliapi.entity.user.DynamicType
import dev.aaa1115910.biliapi.entity.user.toArticleVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpaceDynamicCardTest {
    private fun video(
        badgeText: String = "",
        text: String = "",
        title: String = "标题"
    ) = DynamicItem.DynamicVideoModule(
        aid = 1,
        cid = 0,
        title = title,
        text = text,
        cover = "cover",
        duration = "00:10",
        play = "1",
        danmaku = "0",
        badgeText = badgeText,
    )

    private fun dynamic(
        id: String? = "126188518503022600",
        type: DynamicType,
        majorType: String = "",
        video: DynamicItem.DynamicVideoModule? = null,
        draw: DynamicItem.DynamicDrawModule? = null,
        article: DynamicItem.DynamicArticleModule? = null,
        word: DynamicItem.DynamicWordModule? = null,
        blocked: DynamicItem.DynamicBlockedModule? = null,
        liveRcmd: DynamicItem.DynamicLiveRcmdModule? = null,
        bodyText: String = "",
        pubTime: String = "1天前",
        pubAction: String = ""
    ) = DynamicItem(
        id = id,
        type = type,
        majorType = majorType,
        author = DynamicItem.DynamicAuthorModule("up", "avatar", 1, pubTime, pubAction),
        video = video,
        draw = draw,
        article = article,
        word = word,
        bodyText = bodyText,
        blocked = blocked,
        liveRcmd = liveRcmd
    )

    @Test
    fun `article dynamic uses its own body and badge`() {
        val item = dynamic(
            type = DynamicType.Article,
            majorType = DynamicMajorType.Article,
            article = DynamicItem.DynamicArticleModule(
                title = "文章标题",
                text = "文章摘要",
                url = "//www.bilibili.com/read/cv1",
                label = "专栏",
                id = 1,
                covers = listOf("https://i0.hdslb.com/cover.jpg")
            )
        )

        assertEquals(DynamicCardBody.Article, item.cardBody())
        assertEquals("文章", item.upSpaceTypeLabel())
        assertEquals(DynamicCardAction.Detail, item.cardAction())
    }

    @Test
    fun `opus article reuses the draw content and still opens the dynamic detail`() {
        val item = dynamic(
            type = DynamicType.Article,
            majorType = DynamicMajorType.Opus,
            draw = DynamicItem.DynamicDrawModule(
                title = "美团/淘宝APP闪购嗖：233233",
                text = "B站这个密令靠谱",
                images = pictures(1)
            ),
            // 文章动态即使附带视频，主体仍是文章
            video = video(title = "附件视频")
        )

        assertEquals(DynamicCardBody.Article, item.cardBody())
        assertEquals("文章", item.upSpaceTypeLabel())
        assertEquals(DynamicCardAction.Detail, item.cardAction())
        val draw = assertNotNull(item.articleAsDraw())
        assertEquals("美团/淘宝APP闪购嗖：233233", draw.title)
        assertEquals("B站这个密令靠谱", draw.text)
        assertEquals(1, draw.images.size)
    }

    @Test
    fun `article body can be drawn from the legacy article covers`() {
        val item = dynamic(
            type = DynamicType.Article,
            article = DynamicItem.DynamicArticleModule(
                title = "旧版专栏",
                text = "旧版正文",
                url = "//www.bilibili.com/read/cv1",
                label = "专栏",
                id = 1,
                covers = listOf("https://i0.hdslb.com/cover.jpg")
            )
        )

        val draw = assertNotNull(item.articleAsDraw())
        assertEquals("旧版专栏", draw.title)
        assertEquals("旧版正文", draw.text)
        assertEquals(1, draw.images.size)
    }

    @Test
    fun `draw dynamic opens the article detail`() {
        val item = dynamic(
            type = DynamicType.Draw,
            draw = DynamicItem.DynamicDrawModule(title = "标题", text = "正文", images = emptyList())
        )

        assertEquals("图文", item.upSpaceTypeLabel())
        assertEquals(DynamicCardAction.Article, item.cardAction())
    }

    @Test
    fun `forward dynamic shows no type badge and stays on the dynamic detail`() {
        val item = dynamic(
            type = DynamicType.Forward,
            word = DynamicItem.DynamicWordModule(text = "转发理由")
        )

        // 转发卡正文明说了「转发」，角标再写一遍是重复信息
        assertEquals("", item.upSpaceTypeLabel())
        assertEquals(DynamicCardAction.Detail, item.cardAction())
    }

    @Test
    fun `collab video badge comes straight from the archive badge text`() {
        val item = dynamic(
            type = DynamicType.Av,
            video = video(badgeText = "合作视频")
        )

        assertEquals("合作视频", item.upSpaceTypeLabel())
        assertEquals(DynamicCardAction.Video, item.cardAction())
    }

    @Test
    fun `charging arc video badge comes straight from the archive badge text`() {
        val item = dynamic(
            type = DynamicType.Av,
            video = video(badgeText = "充电专属")
        )

        assertEquals("充电专属", item.upSpaceTypeLabel())
    }

    @Test
    fun `video without an archive badge shows no label`() {
        val item = dynamic(type = DynamicType.Av, video = video())

        assertEquals("", item.upSpaceTypeLabel())
    }

    @Test
    fun `blocked charging dynamic keeps its own label and detail page`() {
        val item = dynamic(
            type = DynamicType.Draw,
            blocked = DynamicItem.DynamicBlockedModule(
                title = "专属动态",
                hintMessage = "充电后可观看",
                iconUrl = "icon"
            )
        )

        assertEquals("充电专属", item.upSpaceTypeLabel())
        assertEquals(DynamicCardAction.Detail, item.cardAction())
    }

    @Test
    fun `pure word dynamic is a draw without a dedicated page`() {
        val item = dynamic(
            type = DynamicType.Word,
            word = DynamicItem.DynamicWordModule(text = "纯文本")
        )

        assertEquals("图文", item.upSpaceTypeLabel())
        assertEquals(DynamicCardAction.Detail, item.cardAction())
    }

    @Test
    fun `header subtitle joins time and action`() {
        val item = dynamic(
            type = DynamicType.Av,
            video = video(),
            pubTime = "3天前",
            pubAction = "投稿了视频"
        )

        assertEquals("3天前 · 投稿了视频", item.headerSubtitle())
    }

    @Test
    fun `header subtitle keeps the action when time is blank`() {
        val item = dynamic(type = DynamicType.LiveRcmd, pubTime = "", pubAction = "直播了")

        assertEquals("直播了", item.headerSubtitle())
    }

    @Test
    fun `header subtitle is blank when both parts are blank`() {
        val item = dynamic(type = DynamicType.Draw, pubTime = "", pubAction = "")

        assertEquals("", item.headerSubtitle())
    }

    @Test
    fun `count formats ten thousands`() {
        assertEquals("", formatCount(0))
        assertEquals("9999", formatCount(9999))
        assertEquals("1万", formatCount(10000))
        assertEquals("1.6万", formatCount(16300))
        assertEquals("16万", formatCount(165000))
        assertEquals("50亿", formatCount(5_000_000_000L))
    }

    @Test
    fun `image preview keeps three images and counts the rest`() {
        val seven = dynamic(type = DynamicType.Draw, draw = drawWithImages(7))
        val twenty = dynamic(type = DynamicType.Draw, draw = drawWithImages(20))

        assertEquals(3, seven.imagePreview().urls.size)
        assertEquals(4, seven.imagePreview().moreCount)
        assertEquals(17, twenty.imagePreview().moreCount)
    }

    @Test
    fun `image preview below four images has no counter`() {
        val three = dynamic(type = DynamicType.Draw, draw = drawWithImages(3))

        assertEquals(3, three.imagePreview().urls.size)
        assertEquals(0, three.imagePreview().moreCount)
    }

    @Test
    fun `draw text blocks drop the title duplicated in the body`() {
        val duplicated = dynamic(
            type = DynamicType.Draw,
            draw = DynamicItem.DynamicDrawModule(
                title = "标题",
                text = "标题 后面还有正文",
                images = emptyList()
            )
        )
        val distinct = dynamic(
            type = DynamicType.Draw,
            draw = DynamicItem.DynamicDrawModule(
                title = "标题",
                text = "另一段正文",
                images = emptyList()
            )
        )

        assertNull(duplicated.drawTextBlocks().title)
        assertEquals("标题 后面还有正文", duplicated.drawTextBlocks().body)
        assertEquals("标题", distinct.drawTextBlocks().title)
        assertEquals("另一段正文", distinct.drawTextBlocks().body)
    }

    @Test
    fun `quoted content prefers the quoted draw text and caps images at three`() {
        val quoted = dynamic(
            id = null,
            type = DynamicType.Draw,
            draw = DynamicItem.DynamicDrawModule(
                title = "原动态标题",
                text = "原动态正文",
                images = pictures(5)
            )
        )
        val item = dynamic(
            type = DynamicType.Forward,
            word = DynamicItem.DynamicWordModule(text = "转发理由")
        ).apply { orig = quoted }

        val content = assertNotNull(item.quotedContent())
        assertEquals("up", content.author)
        assertEquals("原动态标题", content.text)
        assertEquals(3, content.imageUrls.size)
        assertEquals(2, content.moreCount)
        assertNull(item.quotedContent()?.video)
    }

    @Test
    fun `video card puts the dynamic body above the cover`() {
        val item = dynamic(
            type = DynamicType.Av,
            video = video(title = "视频标题", text = "视频简介"),
            bodyText = "友友们周五快乐！！！"
        )

        val texts = item.dynamicVideoTexts()
        // 封面上方是动态正文，不是视频标题
        assertEquals("友友们周五快乐！！！", texts.body)
        assertEquals("视频标题", texts.title)
    }

    @Test
    fun `video card hides a body that repeats the video title`() {
        val item = dynamic(
            type = DynamicType.Av,
            video = video(title = "怎么这个人天天素颜原皮？><", text = "怎么这个人天天素颜原皮？><"),
            bodyText = "怎么这个人天天素颜原皮？><"
        )

        val texts = item.dynamicVideoTexts()
        assertNull(texts.body)
        assertEquals("怎么这个人天天素颜原皮？><", texts.title)
    }

    @Test
    fun `video card drops placeholder bodies`() {
        val dash = dynamic(type = DynamicType.Av, video = video(), bodyText = " - ")
        val emDash = dynamic(type = DynamicType.Av, video = video(), bodyText = "——")
        val ellipsis = dynamic(type = DynamicType.Av, video = video(), bodyText = "…")

        assertNull(dash.dynamicVideoTexts().body)
        assertNull(emDash.dynamicVideoTexts().body)
        assertNull(ellipsis.dynamicVideoTexts().body)
    }

    @Test
    fun `video card never shows the video description`() {
        val item = dynamic(
            type = DynamicType.Av,
            video = video(title = "视频标题", text = "视频简介"),
            bodyText = "动态正文"
        )

        // 简介（archive.desc）不参与渲染，只有正文和标题两段
        assertEquals("动态正文", item.dynamicVideoTexts().body)
        assertEquals("视频标题", item.dynamicVideoTexts().title)
    }

    @Test
    fun `draw blocks drop placeholder text`() {
        val item = dynamic(
            type = DynamicType.Draw,
            draw = DynamicItem.DynamicDrawModule(title = null, text = "-", images = emptyList())
        )

        assertNull(item.drawTextBlocks().body)
    }

    @Test
    fun `quoted draw with an attached video keeps both images and the video`() {
        val quoted = dynamic(
            id = null,
            type = DynamicType.Draw,
            draw = DynamicItem.DynamicDrawModule(
                title = null,
                text = "录舞的间隙我们就这样休息",
                images = pictures(2)
            ),
            video = video(title = "挑战全网最还原", text = "")
        )
        val item = dynamic(
            type = DynamicType.Forward,
            word = DynamicItem.DynamicWordModule(text = "真的非常非常冷了")
        ).apply { orig = quoted }

        val content = assertNotNull(item.quotedContent())
        assertEquals("录舞的间隙我们就这样休息", content.text)
        assertEquals(2, content.imageUrls.size)
        assertEquals("挑战全网最还原", content.video?.title)
        assertEquals("挑战全网最还原", quotedVideoCaption(content))
    }

    @Test
    fun `quoted video keeps its title under the cover`() {
        val quoted = dynamic(
            id = null,
            type = DynamicType.Av,
            video = video(title = "视频标题", text = "视频简介")
        )
        val item = dynamic(type = DynamicType.Forward).apply { orig = quoted }

        val content = assertNotNull(item.quotedContent())
        // 视频标题/简介都不放在封面上方的正文里，标题固定显示在封面下方
        assertEquals("", content.text)
        assertEquals("视频标题", quotedVideoCaption(content))
    }

    @Test
    fun `quoted video shows the quoted dynamic text above the cover`() {
        val quoted = dynamic(
            id = null,
            type = DynamicType.Av,
            video = video(title = "视频标题", text = "视频简介"),
            bodyText = "原动态配文"
        )
        val item = dynamic(type = DynamicType.Forward).apply { orig = quoted }

        val content = assertNotNull(item.quotedContent())
        assertEquals("原动态配文", content.text)
        assertEquals("视频标题", quotedVideoCaption(content))
    }

    @Test
    fun `quoted video stat text follows the web wording`() {
        val video = video(title = "标题").copy(play = "41.2万", danmaku = "174")
        val withoutStat = video(title = "标题").copy(play = "", danmaku = "")

        assertEquals("41.2万观看 174弹幕", quotedVideoStatText(video.toArticleVideo()))
        assertNull(quotedVideoStatText(withoutStat.toArticleVideo()))
    }

    @Test
    fun `draw dynamic with an attached video still renders the draw body`() {
        val item = dynamic(
            type = DynamicType.Draw,
            majorType = DynamicMajorType.Opus,
            draw = DynamicItem.DynamicDrawModule(
                title = null,
                text = "流萤的自拍和直播截图！\n\n视频：BV1Dony6eEk1",
                images = pictures(2)
            ),
            video = video(title = "第一眼我就知道，这是我老婆！！！")
        )

        // 视频只是图文的附件，不能把正文和图片顶掉
        assertEquals(DynamicCardBody.Draw, item.cardBody())
        assertEquals("图文", item.upSpaceTypeLabel())
        assertEquals(DynamicCardAction.Article, item.cardAction())
    }

    @Test
    fun `declared video type renders the video body`() {
        val item = dynamic(
            type = DynamicType.Av,
            majorType = DynamicMajorType.Archive,
            video = video(),
            bodyText = "动态正文"
        )

        assertEquals(DynamicCardBody.Video, item.cardBody())
    }

    @Test
    fun `declared live type renders the live body`() {
        val item = dynamic(
            type = DynamicType.LiveRcmd,
            majorType = DynamicMajorType.LiveRcmd,
            liveRcmd = DynamicItem.DynamicLiveRcmdModule(title = "直播", cover = "c", roomId = 1)
        )

        assertEquals(DynamicCardBody.Live, item.cardBody())
    }

    @Test
    fun `declared draw type without draw content falls back to the text body`() {
        val item = dynamic(
            type = DynamicType.Draw,
            majorType = DynamicMajorType.Opus,
            word = DynamicItem.DynamicWordModule(text = "只有正文")
        )

        assertEquals(DynamicCardBody.Fallback, item.cardBody())
    }

    @Test
    fun `declared video type without an archive falls back to the text body`() {
        val item = dynamic(
            type = DynamicType.Av,
            majorType = DynamicMajorType.Archive,
            word = DynamicItem.DynamicWordModule(text = "只有正文")
        )

        assertEquals(DynamicCardBody.Fallback, item.cardBody())
    }

    @Test
    fun `unknown type with an archive major renders the video body`() {
        val item = dynamic(
            type = DynamicType.None,
            majorType = DynamicMajorType.Archive,
            video = video()
        )

        assertEquals(DynamicCardBody.Video, item.cardBody())
    }

    @Test
    fun `blocked content wins over the declared draw type`() {
        val item = dynamic(
            type = DynamicType.Draw,
            majorType = DynamicMajorType.Blocked,
            blocked = DynamicItem.DynamicBlockedModule(
                title = "专属动态",
                hintMessage = "充电后可观看",
                iconUrl = "icon"
            )
        )

        assertEquals(DynamicCardBody.Blocked, item.cardBody())
    }

    @Test
    fun `draw text drops the video placeholder when the card renders the video`() {
        val withVideo = dynamic(
            type = DynamicType.Draw,
            draw = DynamicItem.DynamicDrawModule(
                title = null,
                text = "流萤的自拍和直播截图！\n\n视频：BV1Dony6eEk1\n",
                images = pictures(2)
            ),
            video = video(title = "第一眼我就知道，这是我老婆！！！")
        )
        val withoutVideo = dynamic(
            type = DynamicType.Draw,
            draw = DynamicItem.DynamicDrawModule(
                title = null,
                text = "正文\n\n视频：BV1Dony6eEk1\n",
                images = pictures(2)
            )
        )

        assertEquals("流萤的自拍和直播截图！", withVideo.drawTextBlocks().body)
        // 没有视频卡时这行是唯一线索，保留
        assertEquals("正文\n\n视频：BV1Dony6eEk1", withoutVideo.drawTextBlocks().body)
    }

    @Test
    fun `quoted draw keeps a single image at full card width`() {
        val quoted = dynamic(
            id = null,
            type = DynamicType.Draw,
            draw = DynamicItem.DynamicDrawModule(title = null, text = "引用正文", images = pictures(1))
        )
        val item = dynamic(type = DynamicType.Forward).apply { orig = quoted }

        assertEquals(1, assertNotNull(item.quotedContent()).imageUrls.size)
    }

    private fun pictures(count: Int) = List(count) {
        dev.aaa1115910.biliapi.entity.Picture(
            url = "https://i0.hdslb.com/p$it.jpg",
            width = 100,
            height = 100,
            key = "k$it"
        )
    }

    private fun drawWithImages(count: Int) = DynamicItem.DynamicDrawModule(
        title = null,
        text = "正文",
        images = pictures(count)
    )
}
