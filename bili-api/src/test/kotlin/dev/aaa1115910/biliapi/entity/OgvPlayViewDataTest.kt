package dev.aaa1115910.biliapi.entity

import dev.aaa1115910.biliapi.http.BiliHttpClient
import dev.aaa1115910.biliapi.http.entity.BiliResponse
import dev.aaa1115910.biliapi.http.entity.video.ClipType
import dev.aaa1115910.biliapi.http.entity.video.OgvPlayViewData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/ogv/player/playview` 是纯 snake_case 且字段比旧 `/pgc/player/web/v2/playurl` 更少，
 * 这里用真实响应片段守住模型里为之放宽的那几处（type 数字、min_buffer_time、
 * 纯音频流缺字段、support_formats 缺 superscript）。
 */
class OgvPlayViewDataTest {
    private val json = BiliHttpClient.json

    private val raw = """
    {
      "code": 0,
      "message": "0",
      "ttl": 1,
      "data": {
        "video_info": {
          "fnval": 143312,
          "type": 2,
          "quality": 32,
          "format": "flv480",
          "timelength": 721920,
          "video_codecid": 7,
          "accept_format": "hdflv2,flv480",
          "support_formats": [
            {"quality": 125, "format": "hdflv2", "description": "真彩 HDR",
             "new_description": "HDR 真彩", "display_desc": "HDR",
             "codecs": ["hvc1.2.4.L150.90"], "need_login": true, "need_vip": true},
            {"quality": 32, "format": "flv480", "description": "清晰 480P",
             "new_description": "480P 标清", "display_desc": "480P",
             "codecs": ["avc1.64001F"]}
          ],
          "dash": {
            "duration": 722,
            "min_buffer_time": 1.5,
            "video": [
              {"id": 32, "base_url": "https://example.com/v.m4s",
               "backup_url": ["https://example.com/v1.m4s", "https://example.com/v2.m4s"],
               "bandwidth": 737936, "mime_type": "video/mp4", "codecs": "avc1.64001F",
               "width": 852, "height": 480, "frame_rate": "25.000", "sar": "1:1",
               "start_with_sap": 1, "segment_base": {"initialization": "0-914", "index_range": "915-2730"},
               "codecid": 7, "md5": "x", "size": 1}
            ],
            "audio": [
              {"id": 30216, "base_url": "https://example.com/a.m4s",
               "backup_url": ["https://example.com/a1.m4s"],
               "bandwidth": 65689, "mime_type": "audio/mp4", "codecs": "mp4a.40.2",
               "segment_base": {"initialization": "0-836", "index_range": "837-2604"},
               "md5": "y", "size": 2}
            ],
            "dolby": {}
          },
          "volume": {"measured_i": -15, "measured_lra": 15.9, "measured_tp": 1.8,
                     "measured_threshold": -25.9, "target_offset": 0.4, "target_i": -14, "target_tp": -1,
                     "multi_scene_args": {"high_dynamic_target_i": "-24", "normal_target_i": "-14",
                                          "undersized_target_i": "-28"}},
          "video_project": true
        }
      }
    }
    """.trimIndent()

    /** type=3：非 DASH，走 durl 分段 MP4（试看），且没有 ahead/vhead */
    private val rawType3 = """
    {
      "code": 0,
      "message": "0",
      "data": {
        "play_video_type": "preview",
        "video_extra": {
          "clip_info": [
            {"clip_type": 1, "start": 0, "end": 87, "toast_text": ""},
            {"clip_type": 2, "start": 7158, "end": 7559, "toast_text": ""}
          ]
        },
        "video_info": {
          "fnval": 143312,
          "type": 3,
          "quality": 32,
          "format": "mp4",
          "timelength": 7558868,
          "video_codecid": 7,
          "accept_format": "mp4,mp4,mp4,mp4,mp4,mp4",
          "support_formats": [
            {"quality": 80, "format": "mp4", "description": "高清 1080P",
             "new_description": "1080P 高清", "display_desc": "1080P",
             "codecs": ["avc1.640032"], "has_preview": true, "need_login": true},
            {"quality": 32, "format": "mp4", "description": "清晰 480P",
             "new_description": "480P 标清", "display_desc": "480P",
             "codecs": ["avc1.64001F"], "has_preview": true}
          ],
          "durl": [
            {"order": 1, "length": 360166, "size": 21544992,
             "url": "https://example.com/p.mp4",
             "backup_url": ["https://example.com/p1.mp4"],
             "md5": "3636516c647da6ad6bf00c5272b6eb9a"}
          ],
          "durls": [
            {"quality": 80, "durl": [{"order": 1, "length": 360166, "size": 70123940,
              "url": "https://example.com/p80.mp4", "backup_url": [], "md5": "a"}]},
            {"quality": 32, "durl": [{"order": 1, "length": 360166, "size": 21544992,
              "url": "https://example.com/p32.mp4", "backup_url": [], "md5": "b"}]}
          ],
          "volume": {"measured_i": -24.2, "multi_scene_args": {"normal_target_i": "-14"}},
          "video_project": true,
          "is_preview": 1
        }
      }
    }
    """.trimIndent()

    @Test
    fun `parses ogv playview response`() {
        val data = json.decodeFromString<BiliResponse<OgvPlayViewData>>(raw).getResponseData()
        val videoInfo = data.videoInfo

        // type 在新接口是数字，旧接口是字符串
        assertEquals("2", videoInfo.type)
        val dash = videoInfo.dash!!
        assertEquals(1.5f, dash.minBufferTime, 1e-6f)

        val video = dash.video.single()
        assertEquals(852, video.width)
        assertEquals(2, video.backupUrl.size)

        // 纯音频流不返回 width/height/frame_rate/sar/start_with_sap/codecid
        val audio = dash.audio!!.single()
        assertEquals(0, audio.width)
        assertEquals(0, audio.height)
        assertEquals("", audio.frameRate)
        assertEquals("", audio.sar)
        assertEquals(0, audio.startWithSap)
        assertEquals(0, audio.codecId)
        assertEquals(1, audio.backupUrl.size)

        assertTrue(videoInfo.supportFormats.all { it.superScript.isEmpty() })
        assertEquals(-14.0, videoInfo.volume!!.multiSceneArgs!!.normalTargetI, 1e-9)
    }

    @Test
    fun `maps ogv video info to play data`() {
        val data = json.decodeFromString<BiliResponse<OgvPlayViewData>>(raw).getResponseData()
        val playData = PlayData.fromOgvPlayViewData(data)

        assertEquals(32, playData.dashVideos.single().quality)
        assertEquals(30216, playData.dashAudios.single().codecId)
        assertEquals(-14.0, playData.volume!!.multiSceneArgs!!.normalTargetI, 1e-9)
    }

    @Test
    fun `parses ogv type3 durl response`() {
        val data = json.decodeFromString<BiliResponse<OgvPlayViewData>>(rawType3).getResponseData()
        val videoInfo = data.videoInfo

        assertEquals("3", videoInfo.type)
        assertNull(videoInfo.dash)
        assertEquals("https://example.com/p.mp4", videoInfo.durl.single().url)
        assertEquals(1, videoInfo.durl.single().backupUrl.size)

        assertEquals(listOf(1, 2), data.videoExtra.clipInfo.map { it.clipType })
    }

    @Test
    fun `maps ogv type3 to preview play data with clip info`() {
        val data = json.decodeFromString<BiliResponse<OgvPlayViewData>>(rawType3).getResponseData()
        val playData = PlayData.fromOgvPlayViewData(data)

        assertTrue(playData.needPay)
        assertEquals(32, playData.dashVideos.single().quality)
        assertEquals("https://example.com/p.mp4", playData.dashVideos.single().baseUrl)
        assertEquals(
            listOf(ClipType.CLIP_TYPE_OP, ClipType.CLIP_TYPE_ED),
            playData.clipInfoList.map { it.clipType }
        )
        assertEquals(0 to 87, playData.clipInfoList.first().start to playData.clipInfoList.first().end)
    }
}
