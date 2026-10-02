package dev.aaa1115910.biliapi.http

import dev.aaa1115910.biliapi.BiliApiConstants
import dev.aaa1115910.biliapi.http.entity.BiliResponse
import dev.aaa1115910.biliapi.http.entity.search.SearchResultData
import dev.aaa1115910.biliapi.http.entity.video.PlayUrlData
import dev.aaa1115910.biliapi.http.entity.video.PlayUrlV2Data
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.URLProtocol

object BiliHttpProxyApi {
    private var client: HttpClient? = null

    fun createClient(proxyServer: String) {
        val proxyServerSplit = proxyServer.split(":")
        val endPoint = proxyServerSplit.first()
        val port = proxyServerSplit.getOrNull(1)?.toInt()
        val isLocalDebug = endPoint == "127.0.0.1"
        client = BiliHttpClient.create(
            host = endPoint,
            port = if (isLocalDebug) 8080 else port,
            // 未指定端口时才用 https；给了端口（含本地调试）沿用 http
            protocol = if (isLocalDebug || port != null) URLProtocol.HTTP else URLProtocol.HTTPS,
            addApiSign = true,
            retryOnException = 2,
        )
    }

    suspend fun getPgcVideoPlayUrl(
        av: Long? = null,
        bv: String? = null,
        epid: Int? = null,
        cid: Long? = null,
        qn: Int? = null,
        fnval: Int? = null,
        fnver: Int? = null,
        fourk: Int? = null,
        session: String? = null,
        supportMultiAudio: Boolean? = null,
        drmTechType: Int? = null,
        fromClient: String? = null,
        sessData: String? = null,
        dedeUserID: Long? = null,
        buvid3: String? = null
    ): BiliResponse<PlayUrlData> = client?.get("/pgc/player/web/playurl") {
        require(av != null || bv != null) { "av and bv cannot be null at the same time" }
        require(epid != null || cid != null) { "epid and cid cannot be null at the same time" }
        av?.let { parameter("avid", it) }
        bv?.let { parameter("bvid", it) }
        epid?.let { parameter("ep_id", it) }
        cid?.let { parameter("cid", it) }
        qn?.let { parameter("qn", it) }
        fnval?.let { parameter("fnval", it) }
        fnver?.let { parameter("fnver", it) }
        fourk?.let { parameter("fourk", it) }
        session?.let { parameter("session", it) }
        supportMultiAudio?.let { parameter("support_multi_audio", it) }
        drmTechType?.let { parameter("drm_tech_type", it) }
        fromClient?.let { parameter("from_client", it) }
        val cookieParts = mutableListOf<String>()
        sessData?.let { cookieParts.add("SESSDATA=$it") }
        dedeUserID?.let { cookieParts.add("DedeUserID=$it") }
        buvid3?.let { cookieParts.add("buvid3=$it") }
        if (cookieParts.isNotEmpty()) header("Cookie", cookieParts.joinToString(";"))
        //必须得加上 referer 才能通过账号身份验证
        header("referer", "https://www.bilibili.com")
    }?.body() ?: throw IllegalStateException("no proxy server")

    suspend fun getPgcVideoPlayUrlV2(
        av: Long? = null,
        bv: String? = null,
        epid: Int? = null,
        cid: Long? = null,
        qn: Int? = null,
        fnval: Int? = null,
        fnver: Int? = null,
        fourk: Int? = null,
        session: String? = null,
        supportMultiAudio: Boolean? = null,
        drmTechType: Int? = null,
        fromClient: String? = null,
        sessData: String? = null,
        buvid3: String? = null,
        gaiaVtoken: String? = null
    ): BiliResponse<PlayUrlV2Data> = client?.get("/pgc/player/web/v2/playurl") {
        require(av != null || bv != null) { "av and bv cannot be null at the same time" }
        require(epid != null || cid != null) { "epid and cid cannot be null at the same time" }
        av?.let { parameter("avid", it) }
        bv?.let { parameter("bvid", it) }
        epid?.let { parameter("ep_id", it) }
        cid?.let { parameter("cid", it) }
        qn?.let { parameter("qn", it) }
        fnval?.let { parameter("fnval", it) }
        fnver?.let { parameter("fnver", it) }
        fourk?.let { parameter("fourk", it) }
        session?.let { parameter("session", it) }
        supportMultiAudio?.let { parameter("support_multi_audio", it) }
        drmTechType?.let { parameter("drm_tech_type", it) }
        fromClient?.let { parameter("from_client", it) }
        gaiaVtoken?.let { parameter("gaia_vtoken", it) }
        val cookieParts = mutableListOf<String>()
        sessData?.let { cookieParts.add("SESSDATA=$it") }
        buvid3?.let { cookieParts.add("buvid3=$it") }
        gaiaVtoken?.let { cookieParts.add("x-bili-gaia-vtoken=$it") }
        if (cookieParts.isNotEmpty()) header("Cookie", cookieParts.joinToString(";"))
        //必须得加上 referer 才能通过账号身份验证
        header("referer", "https://www.bilibili.com")
    }?.body() ?: throw IllegalStateException("no proxy server")

    /**
     * 分类搜索与[keyword]相关的[type]类型的相关结果
     */
    suspend fun searchType(
        keyword: String,
        type: String,
        page: Int = 1,
        tid: Int? = null,
        order: String? = null,
        duration: Int? = null,
        sessData: String? = null,
        buvid3: String? = null
    ): BiliResponse<SearchResultData> = client?.get("/x/web-interface/wbi/search/type") {
        parameter("keyword", keyword)
        parameter("search_type", type)
        parameter("page", page)
        tid?.let { parameter("tids", it) }
        order?.let { parameter("order", it) }
        duration?.let { parameter("duration", it) }
        if (sessData != null) {
            header("Cookie", "SESSDATA=$sessData;buvid3=$buvid3;")
        } else {
            header("Cookie", "buvid3=$buvid3;")
        }
        header("referer", "https://search.bilibili.com/")
    }?.body() ?: throw IllegalStateException("no proxy server")
}