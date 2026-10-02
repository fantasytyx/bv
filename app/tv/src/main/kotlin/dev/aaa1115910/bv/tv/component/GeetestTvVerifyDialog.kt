package dev.aaa1115910.bv.tv.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger("GeetestTvVerify")

/**
 * Geetest 验证结果
 */
data class GeetestTvResult(
    val challenge: String,
    val validate: String,
    val seccode: String,
)

/**
 * 本地交互分支。真实题目类型由极验服务端下发，此处只决定用哪套遥控器操作：
 * 点击题（移动光标 + 点击）或滑块题（拖动 + 提交）。
 */
enum class GeetestInputMode {
    /** 按极验页面检测结果自动切换，正式流程用这个 */
    Auto,

    /** 强制点击交互 */
    Click,

    /** 强制滑块交互 */
    Slider,
}

/** 确认键族：长按切换题类交互的一次性标记，必须在这几个键的 KeyUp 上无条件清除 */
private val GeetestConfirmKeyCodes = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
)

/**
 * TV 端 Geetest 风控验证弹窗
 *
 * 在 WebView 中加载极验 JS SDK，上层覆盖十字光标，
 * 通过遥控器方向键移动光标、确认键触发点击 → 将 touch 事件注入 WebView。
 *
 * 交互方式：
 * - 方向键 (D-Pad) 移动十字光标，长按/连按加速
 * - 点击题：确认键 (Center/Enter) 在当前光标位置点击 WebView
 * - 滑块题：左右键拖动滑块（DOWN→MOVE→UP），确认键提交
 * - 长按确认键可手动切换点击/滑块（仅自动模式），题类探测失败时的兜底
 * - 返回键 (Back) 取消验证
 *
 * @param gt       Geetest gt 参数
 * @param challenge Geetest challenge 参数
 * @param inputMode 本地交互分支，默认按极验检测结果自动切换
 * @param onResult  验证成功后回调
 * @param onDismiss 用户取消/关闭时回调
 */
@Composable
fun GeetestTvVerifyDialog(
    gt: String,
    challenge: String,
    inputMode: GeetestInputMode = GeetestInputMode.Auto,
    onResult: (GeetestTvResult) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            GeetestTvVerifyContent(
                gt = gt,
                challenge = challenge,
                inputMode = inputMode,
                onResult = onResult,
                onDismiss = onDismiss,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GeetestTvVerifyContent(
    gt: String,
    challenge: String,
    inputMode: GeetestInputMode,
    onResult: (GeetestTvResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val callbackScope = rememberCoroutineScope()

    // 状态提示
    var statusText by remember { mutableStateOf("正在加载验证码…") }

    // 极验 bind 模式可能下发点击或滑块；滑块必须发 DOWN→MOVE→UP，只点一下永远过不了
    var sliderMode by remember { mutableStateOf(inputMode == GeetestInputMode.Slider) }
    var dragActive by remember { mutableStateOf(false) }
    var dragDownTime by remember { mutableLongStateOf(0L) }
    val sliderHandle = remember { floatArrayOf(Float.NaN, Float.NaN) }
    // JS 只在页面上真的存在可见把手时才上报坐标，据此区分"确认是滑块题"和"只是猜成滑块题"
    var sliderHandleLive by remember { mutableStateOf(false) }
    // 长按确定键切换题类交互，一次长按只切一次
    var longPressToggled by remember { mutableStateOf(false) }

    // 计时起点，仅用于日志：定位"验证内容迟迟不显示"发生在哪一步
    val startMs = remember { SystemClock.uptimeMillis() }

    // WebView / 光标覆盖层引用：只在 AndroidView.factory 里赋值，不参与重组
    val refs = remember { WebViewRefs() }

    // 移动步长
    val baseStep = with(density) { 6.dp.toPx() }
    val fastStep = with(density) { 20.dp.toPx() }
    val sliderStep = with(density) { 3.dp.toPx() }
    val sliderFastStep = with(density) { 14.dp.toPx() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // 清理 WebView
    DisposableEffect(Unit) {
        onDispose {
            refs.webView?.let { wv ->
                runCatching {
                    wv.removeJavascriptInterface("Android")
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            // 置空，避免 evaluateJavascript 回调在销毁后仍去分发事件
            refs.webView = null
            refs.overlay = null
        }
    }

    // 光标位置由覆盖层自己持有：移动光标不写 Compose 状态，因此方向键连按
    // （每秒十余次）不会触发弹窗重组 + WebView 重新测量
    fun moveCursor(dx: Float, dy: Float, fast: Boolean, precise: Boolean): Pair<Float, Float>? {
        val step = when {
            precise && fast -> sliderFastStep
            precise -> sliderStep
            fast -> fastStep
            else -> baseStep
        }
        refs.overlay?.moveBy(dx * step, dy * step)
        return refs.overlay?.cursorPosition()
    }

    fun moveCursorToSliderHandle() {
        val x = sliderHandle[0]
        val y = sliderHandle[1]
        if (!x.isFinite() || !y.isFinite()) return
        refs.overlay?.setCursorPosition(x, y)
    }

    fun dispatchClickToWebView() {
        val wv = refs.webView ?: return
        val (x, y) = refs.overlay?.cursorPosition() ?: return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
        wv.dispatchSyntheticEvent(down)
        wv.dispatchSyntheticEvent(up)
        logger.debug { "Dispatched click at ($x, $y)" }
    }

    fun dispatchDrag(action: Int, x: Float, y: Float) {
        val wv = refs.webView ?: return
        val downTime = dragDownTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        wv.dispatchSyntheticEvent(event)
    }

    /**
     * 开始拖动。DOWN 必须落在把手当前的真实位置：滑块验证失败后极验会把把手复位，
     * 沿用上一次松开的光标位置发 DOWN 会落在图片上，极验不响应，第二次尝试必然失败。
     */
    fun beginDrag(onStarted: () -> Unit) {
        if (dragActive) return
        val wv = refs.webView ?: return
        wv.evaluateJavascript("window.__bvSliderHandleCenter && window.__bvSliderHandleCenter()") { result ->
            if (dragActive) return@evaluateJavascript
            // 实时坐标取不到说明当下没有可见把手，用缓存位置发 DOWN 必然不被响应
            val center = parseSliderHandleCenter(result) ?: run {
                statusText = "未找到滑块把手，请长按确定键切回点击操作"
                return@evaluateJavascript
            }
            refs.overlay?.setCursorPosition(center.first, center.second)
            dragDownTime = SystemClock.uptimeMillis()
            dragActive = true
            dispatchDrag(MotionEvent.ACTION_DOWN, center.first, center.second)
            statusText = "拖动中：左右键对齐缺口，确定键提交"
            onStarted()
        }
    }

    fun moveAndDrag(dx: Float, fast: Boolean) {
        val position = moveCursor(dx, 0f, fast, precise = true) ?: return
        if (dragActive) dispatchDrag(MotionEvent.ACTION_MOVE, position.first, position.second)
    }

    /** 左右键：确认有把手才拖动，否则退化成移动光标，避免题类误判后左右键被吃掉 */
    fun handleHorizontalKey(dx: Float, fast: Boolean) {
        when {
            !sliderMode -> moveCursor(dx, 0f, fast, precise = false)
            dragActive -> moveAndDrag(dx, fast)
            sliderHandleLive -> beginDrag { moveAndDrag(dx, fast) }
            else -> moveCursor(dx, 0f, fast, precise = false)
        }
    }

    fun toggleSliderMode() {
        sliderMode = !sliderMode
        if (sliderMode) {
            moveCursorToSliderHandle()
            statusText = "已切换为滑块操作：左右键拖动，确定键提交"
        } else {
            statusText = "已切换为点击操作：方向键移动光标，确定键点击"
        }
    }

    fun finishDrag(cancelled: Boolean = false) {
        if (!dragActive) return
        val (x, y) = refs.overlay?.cursorPosition() ?: (0f to 0f)
        dispatchDrag(if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x, y)
        dragActive = false
        dragDownTime = 0L
        statusText = if (cancelled) "拖动已取消" else "已提交滑块位置，正在等待验证结果…"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.45f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .onPreviewKeyEvent { event ->
                val isDown = event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                val isLongPress = event.nativeKeyEvent.repeatCount > 0
                val keyCode = event.nativeKeyEvent.keyCode

                if (!isDown) {
                    // 长按标记必须在同一个键的 KeyUp 上无条件清除，否则会吞掉下一次真实按键
                    if (keyCode in GeetestConfirmKeyCodes) longPressToggled = false
                    return@onPreviewKeyEvent false
                }

                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!sliderMode || !dragActive) moveCursor(0f, -1f, isLongPress, sliderMode)
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!sliderMode || !dragActive) moveCursor(0f, 1f, isLongPress, sliderMode)
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        handleHorizontalKey(-1f, isLongPress)
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        handleHorizontalKey(1f, isLongPress)
                        true
                    }

                    in GeetestConfirmKeyCodes -> {
                        when {
                            // 长按切换题类交互（真实题类由极验下发，探测失败时的兜底；调试强制模式不切）
                            isLongPress -> {
                                if (!longPressToggled && inputMode == GeetestInputMode.Auto) {
                                    longPressToggled = true
                                    toggleSliderMode()
                                }
                            }

                            dragActive -> finishDrag()
                            sliderMode && sliderHandleLive -> statusText = "请先用左右键拖动滑块，再按确定键提交"
                            sliderMode -> {
                                // 题类可能被误判，未确认存在把手时同时按点击提交，点击题仍能过
                                dispatchClickToWebView()
                                statusText = "未检测到滑块，已按点击提交"
                            }

                            else -> dispatchClickToWebView()
                        }
                        true
                    }

                    KeyEvent.KEYCODE_BACK -> {
                        finishDrag(cancelled = true)
                        onDismiss(); true
                    }

                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---- 状态提示 ----
        Text(
            text = statusText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        // ---- WebView + 十字光标叠加 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 4.dp),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    // 固定高度：极验 bind 模式按 WebView 视口居中面板，wrap_content 会让面板被裁掉
                    .height(320.dp)
                    .clip(RoundedCornerShape(8.dp)),
                factory = { ctx ->
                    val webView = WebView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        webChromeClient = WebChromeClient()
                        // 只做计时/报错：gt.js 是 head 里的阻塞脚本，它没下完 JS 就不会执行，
                        // 界面会一直停在"正在加载验证码…"（onPageFinished 覆盖到这一步）
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                logger.info { "Geetest page finished at +${SystemClock.uptimeMillis() - startMs}ms" }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                logger.warn { "Geetest page error: ${error?.errorCode} ${error?.description}" }
                            }
                        }

                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onGeetestResult(
                                    validate: String?,
                                    seccode: String?,
                                    geetestChallenge: String?,
                                ) {
                                    val v = validate.orEmpty().trim()
                                    val s = seccode.orEmpty().trim()
                                    val c = geetestChallenge.orEmpty().trim()
                                    if (v.isBlank() || s.isBlank() || c.isBlank()) return
                                    logger.info { "Geetest verification succeeded" }
                                    // JavascriptInterface 在 WebView 私有线程回调，切回主线程避免跨线程改状态
                                    callbackScope.launch {
                                        onResult(
                                            GeetestTvResult(
                                                challenge = c,
                                                validate = v,
                                                seccode = s
                                            )
                                        )
                                    }
                                }

                                @JavascriptInterface
                                fun onStatusUpdate(text: String?) {
                                    val message = text ?: return
                                    logger.info { "Geetest status at +${SystemClock.uptimeMillis() - startMs}ms: $message" }
                                    callbackScope.launch { statusText = message }
                                }

                                @JavascriptInterface
                                fun onReady() {
                                    logger.info { "Geetest ready, inputMode=$inputMode" }
                                    callbackScope.launch {
                                        statusText = when (inputMode) {
                                            GeetestInputMode.Auto -> "请使用方向键移动光标，确认键点击"
                                            GeetestInputMode.Click -> "点击模式：方向键移动光标，确认键点击"
                                            GeetestInputMode.Slider -> "滑块模式：左右键拖动滑块，确定键提交"
                                        }
                                    }
                                }

                                @JavascriptInterface
                                fun onVerificationType(type: String?) {
                                    val isSlider = type?.trim()?.equals("slider", ignoreCase = true) == true
                                    logger.info { "Geetest reports type=$type, inputMode=$inputMode" }
                                    callbackScope.launch {
                                        // 调试强制模式下不改交互分支，但真实题类与强制模式冲突时要提示，否则用户会一直点不中
                                        if (inputMode != GeetestInputMode.Auto) {
                                            if (isSlider != (inputMode == GeetestInputMode.Slider)) {
                                                val actual = if (isSlider) "滑块" else "点击"
                                                statusText = "注意：本题实际为${actual}题，当前强制模式可能无法通过"
                                            }
                                            return@launch
                                        }
                                        // 拖动中不切分支，否则手里的滑块会被中途切走
                                        if (dragActive) return@launch
                                        sliderMode = isSlider
                                        if (isSlider) {
                                            moveCursorToSliderHandle()
                                            statusText = "滑块验证：左右键拖动滑块，确定键提交"
                                        } else {
                                            sliderHandleLive = false
                                            statusText = "点击验证：方向键移动光标，确认键点击"
                                        }
                                    }
                                }

                                @JavascriptInterface
                                fun onSliderPosition(x: Double, y: Double) {
                                    if (!x.isFinite() || !y.isFinite()) return
                                    callbackScope.launch {
                                        sliderHandle[0] = x.toFloat()
                                        sliderHandle[1] = y.toFloat()
                                        sliderHandleLive = true
                                        if (sliderMode && !dragActive) moveCursorToSliderHandle()
                                    }
                                }
                            },
                            "Android"
                        )

                        loadDataWithBaseURL(
                            "https://api.bilibili.com/",
                            buildGeetestHtml(gt, challenge),
                            "text/html",
                            "utf-8",
                            null,
                        )

                        refs.webView = this
                    }

                    val overlay = CrosshairOverlayView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        refs.overlay = this
                    }

                    FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        addView(webView)
                        addView(overlay)
                    }
                },
            )
        }

        // ---- 操作提示 ----
        Text(
            text = when {
                sliderMode && inputMode == GeetestInputMode.Auto ->
                    "左右键拖动滑块 ｜ 确认键提交 ｜ 长按确认键切换 ｜ 返回键取消"

                sliderMode -> "左右键拖动滑块 ｜ 确认键提交 ｜ 返回键取消"
                inputMode == GeetestInputMode.Auto ->
                    "方向键移动光标 ｜ 确认键点击 ｜ 长按确认键切换 ｜ 返回键取消"

                else -> "方向键移动光标 ｜ 确认键点击 ｜ 返回键取消"
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 分发合成触摸事件后立即回收。WebView 在 onTouchEvent 内会拷贝事件，
 * 若将来出现偶发 `IllegalStateException: recycled event`，把回收挪到下一帧即可。
 */
private fun WebView.dispatchSyntheticEvent(event: MotionEvent) {
    try {
        dispatchTouchEvent(event)
    } finally {
        event.recycle()
    }
}

/**
 * 解析 `__bvSliderHandleCenter()` 的返回值（`"x,y"`，缺失时为 `"null"`）。
 * evaluateJavascript 会把字符串结果再包一层引号。
 */
private fun parseSliderHandleCenter(raw: String?): Pair<Float, Float>? {
    val text = raw?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "null" }
        ?: return null
    val parts = text.split(',')
    if (parts.size != 2) return null
    val x = parts[0].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
    val y = parts[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
    return x to y
}

/** WebView / 覆盖层引用容器：普通字段即可，写入不需要触发重组 */
private class WebViewRefs {
    var webView: WebView? = null
    var overlay: CrosshairOverlayView? = null
}

/**
 * 原生 View 十字光标覆盖层，渲染在 WebView 之上。
 */
private class CrosshairOverlayView(context: Context) : View(context) {
    private var cx = 0f
    private var cy = 0f
    private var centered = false

    private val density = context.resources.displayMetrics.density
    private val armLen = 18f * density
    private val gap = 5f * density
    private val strokeW = 2f * density
    private val shadowW = 3.5f * density
    private val dotRadius = 3f * density
    private val dotShadowRadius = 4f * density
    private val circleRadius = armLen + gap

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = strokeW
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = shadowW
    }
    private val dotWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val dotShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }

    /** 相对移动，越界贴边 */
    fun moveBy(dx: Float, dy: Float) {
        centerIfNeeded()
        cx = (cx + dx).coerceIn(0f, width.toFloat())
        cy = (cy + dy).coerceIn(0f, height.toFloat())
        invalidate()
    }

    /** 直接定位（滑块把手对齐用），越界贴边 */
    fun setCursorPosition(x: Float, y: Float) {
        centerIfNeeded()
        cx = if (width > 0) x.coerceIn(0f, width.toFloat()) else x
        cy = if (height > 0) y.coerceIn(0f, height.toFloat()) else y
        centered = true
        invalidate()
    }

    /** 未完成首次布局时返回 null（此时点击没有意义） */
    fun cursorPosition(): Pair<Float, Float>? {
        centerIfNeeded()
        return if (centered) cx to cy else null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (centered) {
            cx = cx.coerceIn(0f, w.toFloat())
            cy = cy.coerceIn(0f, h.toFloat())
        }
        centerIfNeeded()
        invalidate()
    }

    private fun centerIfNeeded() {
        if (centered || width <= 0 || height <= 0) return
        cx = width / 2f
        cy = height / 2f
        centered = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!centered) return

        // 阴影线
        canvas.drawLine(cx, cy - gap - armLen, cx, cy - gap, shadowPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + gap + armLen, shadowPaint)
        canvas.drawLine(cx - gap - armLen, cy, cx - gap, cy, shadowPaint)
        canvas.drawLine(cx + gap, cy, cx + gap + armLen, cy, shadowPaint)

        // 白色十字
        canvas.drawLine(cx, cy - gap - armLen, cx, cy - gap, whitePaint)
        canvas.drawLine(cx, cy + gap, cx, cy + gap + armLen, whitePaint)
        canvas.drawLine(cx - gap - armLen, cy, cx - gap, cy, whitePaint)
        canvas.drawLine(cx + gap, cy, cx + gap + armLen, cy, whitePaint)

        // 中心点
        canvas.drawCircle(cx, cy, dotShadowRadius, dotShadowPaint)
        canvas.drawCircle(cx, cy, dotRadius, dotWhitePaint)

        // 外圈虚线
        canvas.drawCircle(cx, cy, circleRadius, dashPaint)
    }
}

/**
 * 构建加载极验验证码的 HTML 页面。
 *
 * 使用极验 JS SDK (`gt.js`) 初始化验证并自动弹出验证窗口，
 * 用户在 WebView 中完成点选后，通过 JS bridge 回调原生代码。
 */
private fun buildGeetestHtml(gt: String, challenge: String): String {
    // 防止参数注入
    val safeGt = gt.replace("\\", "\\\\").replace("'", "\\'").replace("<", "&lt;")
    val safeChallenge = challenge.replace("\\", "\\\\").replace("'", "\\'").replace("<", "&lt;")
    return """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no"/>
  <script src="https://static.geetest.com/static/tools/gt.js"></script>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    html, body {
      width: 100%;
      height: 100%;
      background: transparent;
      font-family: sans-serif;
      overflow: hidden;
    }
    body {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 4px;
    }
    #captcha {
      width: 100%;
      min-height: 300px;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    /*
     * bind 模式会把验证面板按 WebView 视口居中。不能改成 relative：
     * 极验保留的 top/left 偏移会叠加到普通文档流位置，导致面板下移并被裁切。
     * 注意：这里依赖极验未公开的类名，极验前端改版后会静默失效（表现为面板被裁切），
     * 遇到该类问题先查这段样式。
     */
    .geetest_panel { position: fixed !important; }
    .geetest_panel_box { position: absolute !important; }
    /* 隐藏极验自带的关闭按钮，用遥控器返回键代替 */
    .geetest_panel_ghost,
    .geetest_close { display: none !important; }
  </style>
</head>
<body>
  <div id="captcha"></div>
  <script>
    (function() {
      function notify(msg) {
        try { window.Android.onStatusUpdate(msg); } catch(e) {}
      }
      function reportVerificationType(type) {
        try { window.Android.onVerificationType(type); } catch(e) {}
      }
      function findSliderHandle() {
        var selectors = [
          '.geetest_slider_button',
          '[class*="slider_button"]',
          '[class*="slider-btn"]'
        ];
        for (var i = 0; i < selectors.length; i++) {
          var nodes = document.querySelectorAll(selectors[i]);
          for (var j = 0; j < nodes.length; j++) {
            var node = nodes[j];
            var rect = node.getBoundingClientRect();
            if (rect.width <= 0 || rect.height <= 0) continue;
            // 隐藏或残留节点不算把手，否则会把点击题误判成滑块题
            var style = window.getComputedStyle(node);
            if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') continue;
            return node;
          }
        }
        return null;
      }
      function sliderHandleCenter() {
        var handle = findSliderHandle();
        if (!handle) return null;
        var r = handle.getBoundingClientRect();
        var scale = window.devicePixelRatio || 1;
        return {
          x: (r.left + r.width / 2) * scale,
          y: (r.top + r.height / 2) * scale
        };
      }
      // 原生每次开始拖动前同步调用，取把手的实时坐标（失败重试后把手会复位）
      window.__bvSliderHandleCenter = function() {
        var c = sliderHandleCenter();
        return c ? (c.x + ',' + c.y) : 'null';
      };
      function reportSliderPosition(attempt) {
        var c = sliderHandleCenter();
        if (c) {
          reportVerificationType('slider');
          try { window.Android.onSliderPosition(c.x, c.y); } catch(e) {}
          return;
        }
        // 1 秒内没找到把手就先按点击题上报（否则探测失败会让用户点不动），
        // 同时继续轮询：滑块晚一点渲染出来会自动切回滑块分支
        if (attempt === 10) reportVerificationType('click');
        if (attempt < 150) {
          setTimeout(function() { reportSliderPosition(attempt + 1); }, 100);
        }
      }
      if (typeof initGeetest !== 'function') {
        notify('验证码脚本加载失败，请检查网络');
        return;
      }
      notify('正在初始化验证…');
      initGeetest({
        gt: '$safeGt',
        challenge: '$safeChallenge',
        new_captcha: true,
        product: 'bind',
        offline: false,
        https: true
      }, function(captchaObj) {
        captchaObj.appendTo('#captcha');
        captchaObj.onReady(function() {
          try { window.Android.onReady(); } catch(e) {}
          // 自动弹出验证
          captchaObj.verify();
          setTimeout(function() { reportSliderPosition(0); }, 100);
        });
        captchaObj.onSuccess(function() {
          var res = captchaObj.getValidate();
          if (!res) return;
          notify('验证成功，正在提交…');
          try {
            window.Android.onGeetestResult(
              res.geetest_validate,
              res.geetest_seccode,
              res.geetest_challenge
            );
          } catch(e) {}
        });
        captchaObj.onError(function(e) {
          notify('验证出错：' + (e && (e.msg || e.error_code) || '未知错误'));
          // 出错后极验可能重建面板，重新探测把手位置
          setTimeout(function() { reportSliderPosition(0); }, 300);
        });
        captchaObj.onClose(function() {
          // 极验面板关闭后重新弹出，防止误触关闭
          notify('验证已关闭，正在重新打开…');
          setTimeout(function() {
            captchaObj.verify();
            reportSliderPosition(0);
          }, 500);
        });
      });
    })();
  </script>
</body>
</html>
    """.trimIndent()
}
