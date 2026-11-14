package dev.aaa1115910.biliapi.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class BiliHttpApiOpusFavoriteRequestTest {
    private val json = Json

    @Test
    fun `favorite body matches the web request`() {
        val root = json.parseToJsonElement(
            BiliHttpApi.buildOpusActionBody(opusId = OPUS_ID, favorite = true)
        ).jsonObject

        val entity = root.getValue("entity").jsonObject
        assertEquals(OPUS_ID, entity.getValue("object_id_str").jsonPrimitive.content)
        assertEquals(2, entity.getValue("type").jsonObject.getValue("biz").jsonPrimitive.int)
        assertEquals(3, root.getValue("action").jsonPrimitive.int)

        val meta = root.getValue("meta").jsonObject
        assertEquals("333.1369.0.0", meta.getValue("spmid").jsonPrimitive.content)
        assertEquals("333.1387.0.0", meta.getValue("from_spmid").jsonPrimitive.content)
        assertEquals("unknown", meta.getValue("from").jsonPrimitive.content)
    }

    @Test
    fun `unfavorite uses action 4`() {
        val root = json.parseToJsonElement(
            BiliHttpApi.buildOpusActionBody(opusId = OPUS_ID, favorite = false)
        ).jsonObject

        assertEquals(4, root.getValue("action").jsonPrimitive.int)
    }

    @Test
    fun `custom spmid is kept in meta`() {
        val body = BiliHttpApi.buildOpusActionBody(
            opusId = OPUS_ID,
            favorite = true,
            spmid = "333.1234.0.0",
            fromSpmid = "333.5678.0.0"
        )

        val meta = json.parseToJsonElement(body).jsonObject.getValue("meta").jsonObject
        assertEquals("333.1234.0.0", meta.getValue("spmid").jsonPrimitive.content)
        assertEquals("333.5678.0.0", meta.getValue("from_spmid").jsonPrimitive.content)
    }

    private companion object {
        const val OPUS_ID = "1224454408301969417"
    }
}
