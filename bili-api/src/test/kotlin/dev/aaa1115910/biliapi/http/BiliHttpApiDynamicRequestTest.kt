package dev.aaa1115910.biliapi.http

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BiliHttpApiDynamicRequestTest {
    @Test
    fun `UP feed adds host mid and matching identity cookies`() {
        val request = HttpRequestBuilder()

        with(BiliHttpApi) {
            request.configureDynamicFeedRequest(
                timezoneOffset = -480,
                type = "all",
                page = 2,
                offset = "cursor-1",
                sessData = "session",
                hostMid = 5028996L,
                dedeUserID = 987654L,
                dedeUserIDCkMd5 = "user-hash"
            )
        }

        assertEquals("5028996", request.url.parameters["host_mid"])
        assertEquals("2", request.url.parameters["page"])
        assertEquals("cursor-1", request.url.parameters["offset"])
        assertEquals(
            "SESSDATA=session; DedeUserID=987654; DedeUserID__ckMd5=user-hash",
            request.headers[HttpHeaders.Cookie]
        )
    }

    @Test
    fun `UP space feed uses the space endpoint instead of the followed feed`() {
        assertEquals("/x/polymer/web-dynamic/v1/feed/space", BiliHttpApi.UpSpaceDynamicFeedPath)
    }

    @Test
    fun `UP space feed pages by offset only`() {
        val request = HttpRequestBuilder()

        with(BiliHttpApi) {
            request.configureSpaceDynamicFeedRequest(
                timezoneOffset = -480,
                offset = "cursor-1",
                sessData = "space-session",
                hostMid = 2009929L,
                dedeUserID = 123L,
                dedeUserIDCkMd5 = "space-hash",
                features = "itemOpusStyle",
                spaceLoginGroup = 2,
                platform = "web"
            )
        }

        assertEquals("2009929", request.url.parameters["host_mid"])
        assertEquals("cursor-1", request.url.parameters["offset"])
        assertEquals("2", request.url.parameters["space_login_group"])
        assertEquals("web", request.url.parameters["platform"])
        assertEquals("-480", request.url.parameters["timezone_offset"])
        // /feed/space 不接受 page/type，传了会被当成非法参数
        assertNull(request.url.parameters["page"])
        assertNull(request.url.parameters["type"])
        assertEquals(
            "SESSDATA=space-session; DedeUserID=123; DedeUserID__ckMd5=space-hash",
            request.headers[HttpHeaders.Cookie]
        )
    }

    @Test
    fun `UP space feed always sends an offset, empty on the first page`() {
        val request = HttpRequestBuilder()

        with(BiliHttpApi) {
            request.configureSpaceDynamicFeedRequest(
                timezoneOffset = -480,
                offset = null,
                sessData = "space-session",
                hostMid = 2009929L,
                dedeUserID = 123L,
                dedeUserIDCkMd5 = "space-hash",
                features = null,
                spaceLoginGroup = 2,
                platform = "web"
            )
        }

        assertEquals("", request.url.parameters["offset"])
        assertNull(request.url.parameters["features"])
    }

    @Test
    fun `opus detail uses the opus endpoint with web features and identity cookies`() {
        assertEquals("/x/polymer/web-dynamic/v1/opus/detail", BiliHttpApi.OpusDetailPath)

        val request = HttpRequestBuilder()

        with(BiliHttpApi) {
            request.configureOpusDetailRequest(
                timezoneOffset = -480,
                opusId = "1266038303121997840",
                sessData = "opus-session",
                dedeUserID = 123L,
                dedeUserIDCkMd5 = "opus-hash"
            )
        }

        assertEquals("-480", request.url.parameters["timezone_offset"])
        assertEquals("1266038303121997840", request.url.parameters["id"])
        // 图文详情页的收藏数只在 opus 详情里，features 必须与网页端一致
        assertEquals("htmlNewStyle", request.url.parameters["features"])
        assertEquals(
            "SESSDATA=opus-session; DedeUserID=123; DedeUserID__ckMd5=opus-hash",
            request.headers[HttpHeaders.Cookie]
        )
    }

    @Test
    fun `global feed keeps its existing query and session cookie`() {
        val request = HttpRequestBuilder()

        with(BiliHttpApi) {
            request.configureDynamicFeedRequest(
                timezoneOffset = -480,
                type = "all",
                page = 1,
                offset = null,
                sessData = "global-session",
                hostMid = null,
                dedeUserID = null,
                dedeUserIDCkMd5 = null
            )
        }

        assertEquals("-480", request.url.parameters["timezone_offset"])
        assertEquals("all", request.url.parameters["type"])
        assertEquals("1", request.url.parameters["page"])
        assertNull(request.url.parameters["host_mid"])
        assertNull(request.url.parameters["offset"])
        assertEquals("SESSDATA=global-session;", request.headers[HttpHeaders.Cookie])
    }
}
