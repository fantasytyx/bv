package dev.aaa1115910.bv.tv.screens.user

import android.content.Context
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.entity.proxy.ProxyArea
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.tv.activities.dynamic.DynamicDetailActivity
import dev.aaa1115910.bv.tv.activities.user.ArticleActivity
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity

/** 动态卡片的点击路由；[preloadFrom] 提供视频详情页左右滑动的同批卡片 */
fun openDynamic(
    context: Context,
    dynamic: DynamicItem,
    videoInfoRepository: VideoInfoRepository,
    preloadFrom: List<DynamicItem>
) {
    val video = dynamic.video
    when (dynamic.cardAction()) {
        DynamicCardAction.Article -> dynamic.id?.toLongOrNull()
            ?.let { ArticleActivity.actionStart(context, it) }

        DynamicCardAction.Video -> video?.let { playVideo ->
            videoInfoRepository.setPreloadedVideoList(
                preloadFrom.mapNotNull { item ->
                    item.video?.let { videoItem ->
                        VideoCardData(
                            avid = videoItem.aid,
                            title = videoItem.title,
                            cover = videoItem.cover,
                            upName = "",
                            play = videoItem.playCount,
                            danmaku = videoItem.danmakuCount,
                            time = videoItem.durationSeconds * 1000L,
                            pubTime = item.author.pubTime
                        )
                    }
                }
            )
            VideoInfoActivity.actionStart(
                context = context,
                aid = playVideo.aid,
                proxyArea = ProxyArea.checkProxyArea(playVideo.title)
            )
        }

        DynamicCardAction.Detail -> dynamic.id?.let {
            DynamicDetailActivity.actionStart(context, it)
        }
    }
}
