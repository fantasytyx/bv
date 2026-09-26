package dev.aaa1115910.bv.player.entity

/**
 * 播放器控制栏按钮配置
 * @param id 按钮 ID
 * @param hidden 是否被用户隐藏
 * @param isDefaultFocus 是否为默认焦点按钮
 */
data class ControllerButtonConfig(
    val id: String,
    val hidden: Boolean = false,
    val isDefaultFocus: Boolean = false
)

/**
 * 所有控制栏按钮 ID（默认顺序）
 */
val ALL_CONTROLLER_BUTTON_IDS = listOf(
    "nextVideo", "refresh", "speed", "liveLine", "upSpace", "rotation",
    "comment", "subtitle", "danmaku", "playlist", "related", "playMode",
    "videoDetail", "description", "settings", "resolution", "audio"
)

/**
 * 新增按钮的默认隐藏列表：出现在这里的新按钮在用户未保存任何配置时默认隐藏，
 * 同时在老用户升级后通过 insertMissingButtons 追加时也保持隐藏。
 */
val DEFAULT_HIDDEN_CONTROLLER_BUTTON_IDS = setOf("audio", "videoDetail")

/**
 * 解析控制栏按钮配置字符串
 *
 * 格式：逗号分隔的按钮 ID，前缀含义：
 * - 无前缀：可见
 * - `-` 前缀：隐藏
 * - `*` 前缀：默认焦点
 * - `*-` 前缀：隐藏且为默认焦点
 *
 * 例如："refresh,speed,-rotation,*danmaku"
 */
fun parseControllerButtonsOrder(orderString: String): List<ControllerButtonConfig> {
    if (orderString.isBlank()) return emptyList()
    val configs = orderString.split(",")
        .mapNotNull { token ->
            val trimmed = token.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val isDefaultFocus = trimmed.startsWith("*")
            val afterStar = if (isDefaultFocus) trimmed.substring(1) else trimmed
            val isHidden = afterStar.startsWith("-")
            val rawId = if (isHidden) afterStar.substring(1) else afterStar
            // 向后兼容：旧配置中的 "loop" 映射为 "playMode"
            val id = if (rawId == "loop") "playMode" else rawId
            if (id.isEmpty() || !ALL_CONTROLLER_BUTTON_IDS.contains(id)) return@mapNotNull null
            ControllerButtonConfig(id, isHidden, isDefaultFocus)
        }
    return insertMissingButtons(configs)
}

/**
 * 将缺失的新按钮按默认顺序插入到对应位置
 */
private fun insertMissingButtons(configs: List<ControllerButtonConfig>): List<ControllerButtonConfig> {
    val existingIds = configs.map { it.id }.toSet()
    val missingIds = ALL_CONTROLLER_BUTTON_IDS.filter { it !in existingIds }
    if (missingIds.isEmpty()) return configs

    val result = configs.toMutableList()
    for (missingId in missingIds) {
        val defaultIndex = ALL_CONTROLLER_BUTTON_IDS.indexOf(missingId)
        var insertIndex = result.size
        for (i in result.indices.reversed()) {
            val idxInDefault = ALL_CONTROLLER_BUTTON_IDS.indexOf(result[i].id)
            if (idxInDefault < defaultIndex) {
                insertIndex = i + 1
                break
            }
            if (i == 0) insertIndex = 0
        }
        result.add(
            insertIndex,
            ControllerButtonConfig(
                id = missingId,
                hidden = missingId in DEFAULT_HIDDEN_CONTROLLER_BUTTON_IDS
            )
        )
    }
    return result
}

/**
 * 将控制栏按钮配置列表序列化为字符串
 */
fun serializeControllerButtonsOrder(configs: List<ControllerButtonConfig>): String {
    return configs
        .filter { ALL_CONTROLLER_BUTTON_IDS.contains(it.id) }
        .joinToString(",") { config ->
            buildString {
                if (config.isDefaultFocus) append("*")
                if (config.hidden) append("-")
                append(config.id)
            }
        }
}

/**
 * 获取用于编辑的完整按钮配置列表
 * 如果存储的配置为空，返回所有按钮的默认配置；
* 如果有值，复用 parseControllerButtonsOrder（已自动补充缺失按钮）。
 */
fun getControllerButtonConfigsForEditing(orderString: String): List<ControllerButtonConfig> {
    val configs = parseControllerButtonsOrder(orderString)
    if (configs.isEmpty()) {
        return ALL_CONTROLLER_BUTTON_IDS.map { id ->
            ControllerButtonConfig(id, hidden = id in DEFAULT_HIDDEN_CONTROLLER_BUTTON_IDS)
        }
    }
    return configs
}

/**
 * 获取按钮的中文显示名称
 */
fun getControllerButtonDisplayName(id: String): String {
    return when (id) {
        "nextVideo" -> "下一个视频"
        "refresh" -> "刷新"
        "speed" -> "播放速度"
        "liveLine" -> "直播线路"
        "upSpace" -> "UP主空间"
        "rotation" -> "画面旋转"
        "comment" -> "评论"
        "subtitle" -> "字幕"
        "danmaku" -> "弹幕"
        "playlist" -> "播放列表"
        "related" -> "相关推荐"
        "playMode" -> "播放模式"
        "videoDetail" -> "视频详情"
        "description" -> "简介"
        "settings" -> "设置"
        "resolution" -> "画质"
        "audio" -> "音频编码"
        else -> id
    }
}
