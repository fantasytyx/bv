package dev.aaa1115910.bv.util

fun String.resizedImageUrl(size: ImageSize): String {
    return when (size) {
        ImageSize.Default -> this
        else -> "$this@${size.sizeString}.webp"
    }
}

enum class ImageSize(val sizeString: String) {
    Default(""),
    Cover("180h_288w_1c"),
    SmallVideoCardCover("400h_640w_1c"),
    SeasonCoverThumbnail("466h_622w"),
    LargeCover("480h_768w_1c"),
    Icon("100h_100w_1c"),
    UgcEpisodeCover("384w_216h_1c"),

    /** 图文卡片封面：卡片约 210×180dp，按 dpr=2 取 420×360（7:6） */
    ArticleCardCover("420w_360h_1c"),

    /** 合集/系列卡片封面：16:9，卡片约 210dp 宽，按 dpr=2 取 480×270 */
    CollectionCardCover("480w_270h_1c"),

    /** 动态单图：显示比例跟随原图，只给宽度让服务端等比缩放 */
    DynamicSingleImage("640w"),

    /** 动态卡片里的 1:1 三图宫格，单张约 92dp，按 dpr=2 取 360×360 */
    DynamicImageCover("360w_360h_1c"),

    /** 图文/动态详情页的三列 1:1 大图，单张约 280dp，按 dpr=2 取 600×600 */
    DetailImageCover("600w_600h_1c"),

    /** 16:9 横向大图（直播封面、详情页视频卡），约 290dp 宽，按 dpr=2 取 640×360 */
    WideCover("640w_360h_1c")
}
