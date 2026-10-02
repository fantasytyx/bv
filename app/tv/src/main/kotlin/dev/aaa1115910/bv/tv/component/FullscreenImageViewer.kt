package dev.aaa1115910.bv.tv.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import dev.aaa1115910.biliapi.entity.Picture
import kotlin.math.max
import dev.aaa1115910.bv.util.isDpadDown
import dev.aaa1115910.bv.util.isDpadLeft
import dev.aaa1115910.bv.util.isDpadRight
import dev.aaa1115910.bv.util.isDpadUp
import dev.aaa1115910.bv.util.isKeyDown

/**
 * 全屏图片查看器
 *
 * @param pictures 图片列表
 * @param initialIndex 初始显示的图片索引
 * @param onDismiss 关闭回调
 * @param bitmapOverrides 指定索引使用内存位图替代 URL 加载
 */
@Composable
fun FullscreenImageViewer(
    pictures: List<Picture>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    bitmapOverrides: Map<Int, ImageBitmap> = emptyMap()
) {
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var viewportWidthPx by remember { mutableFloatStateOf(0f) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    var imageWidthPx by remember { mutableFloatStateOf(0f) }
    var imageHeightPx by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val panStepPx = with(density) { 40.dp.toPx() }

    val focusRequester = remember { FocusRequester() }

    val isBitmapItem = currentIndex in bitmapOverrides
    val painter = if (isBitmapItem) {
        BitmapPainter(bitmapOverrides[currentIndex]!!)
    } else {
        rememberAsyncImagePainter(model = pictures[currentIndex].url)
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(currentIndex, painter.intrinsicSize) {
        val intrinsic = painter.intrinsicSize
        if (intrinsic.width > 0f && intrinsic.height > 0f &&
            viewportWidthPx > 0f && viewportHeightPx > 0f
        ) {
            imageWidthPx = intrinsic.width
            imageHeightPx = intrinsic.height
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (!event.isKeyDown()) return@onPreviewKeyEvent false

                    fun clampOffset() {
                        if (imageWidthPx <= 0f || imageHeightPx <= 0f) return
                        val scaledWidth = scale * imageWidthPx
                        val scaledHeight = scale * imageHeightPx
                        if (scaledWidth <= viewportWidthPx) {
                            offsetX = 0f
                        } else {
                            val maxOffsetX = max(
                                scaledWidth / 2f - 0.42f * viewportWidthPx,
                                0.08f * viewportWidthPx
                            )
                            offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                        }
                        if (scaledHeight <= viewportHeightPx) {
                            offsetY = 0f
                        } else {
                            val maxOffsetY = max(
                                scaledHeight / 2f - 0.42f * viewportHeightPx,
                                0.08f * viewportHeightPx
                            )
                            offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                        }
                    }

                    when (event.key) {
                        Key.Back -> {
                            onDismiss()
                            true
                        }
                        Key.Enter, Key.DirectionCenter -> {
                            if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent false
                            val levels = listOf(1f, 1.5f, 2f, 3f)
                            val idx = levels.indexOf(scale).coerceAtLeast(0)
                            val newScale = levels[(idx + 1) % levels.size]
                            val ratio = newScale / scale
                            offsetX *= ratio
                            offsetY *= ratio
                            scale = newScale
                            clampOffset()
                            true
                        }
                        else -> {
                            if (scale > 1f) {
                                when {
                                    event.isDpadLeft() -> offsetX -= panStepPx
                                    event.isDpadRight() -> offsetX += panStepPx
                                    event.isDpadUp() -> offsetY -= panStepPx
                                    event.isDpadDown() -> offsetY += panStepPx
                                    else -> return@onPreviewKeyEvent false
                                }
                                clampOffset()
                                true
                            } else {
                                when {
                                    event.isDpadLeft() -> {
                                        if (currentIndex > 0) {
                                            currentIndex--
                                            scale = 1f; offsetX = 0f; offsetY = 0f
                                        }
                                        true
                                    }
                                    event.isDpadRight() -> {
                                        if (currentIndex < pictures.size - 1) {
                                            currentIndex++
                                            scale = 1f; offsetX = 0f; offsetY = 0f
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                        }
                    }
                },
            onClick = { /* 消费点击事件 */ },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                focusedContainerColor = Color.Black.copy(alpha = 0.5f),
                pressedContainerColor = Color.Transparent
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(0.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        viewportWidthPx = size.width.toFloat()
                        viewportHeightPx = size.height.toFloat()
                    },
                contentAlignment = Alignment.Center
            ) {
                // 图片（加载指示器仅用于远程图片）
                if (!isBitmapItem) {
                    val painterState = (painter as AsyncImagePainter).state
                    if (painterState is AsyncImagePainter.State.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = Color.White
                        )
                    }
                }

                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                    contentScale = ContentScale.Inside
                )

                // 页码指示器
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${currentIndex + 1}/${pictures.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Icon(
                            imageVector = Icons.Filled.ZoomIn,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(2.dp))
                        Text(
                            text = "${(scale * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
