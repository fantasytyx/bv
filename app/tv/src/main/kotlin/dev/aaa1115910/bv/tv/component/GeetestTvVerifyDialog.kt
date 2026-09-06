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
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * TV 端 Geetest 风控验证弹窗
 *
 * 在 WebView 中加载极验 JS SDK，上层覆盖十字光标，
 * 通过遥控器方向键移动光标、确认键触发点击 → 将 touch 事件注入 WebView。
 *
 * 交互方式：
 * - 方向键 (D-Pad) 移动十字光标，长按/连按加速
 * - 确认键 (Center/Enter) 在当前光标位置点击 WebView
 * - 返回键 (Back) 取消验证
 *
 * @param gt       Geetest gt 参数
 * @param challenge Geetest challenge 参数
 * @param onResult  验证成功后回调
 * @param onDismiss 用户取消/关闭时回调
 */
@Composable
fun GeetestTvVerifyDialog(
    gt: String,
    challenge: String,
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
    onResult: (GeetestTvResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }

    // WebView 容器实际像素尺寸
    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    // 光标位置 (像素坐标，相对于 WebView 容器)
    var cursorX by remember { mutableFloatStateOf(0f) }
    var cursorY by remember { mutableFloatStateOf(0f) }
    var cursorInitialized by remember { mutableStateOf(false) }

    // 状态提示
    var statusText by remember { mutableStateOf("正在加载验证码…") }

    // WebView 引用
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 光标覆盖层引用
    var overlayRef by remember { mutableStateOf<CrosshairOverlayView?>(null) }

    // 移动步长
    val baseStep = with(density) { 6.dp.toPx() }
    val fastStep = with(density) { 20.dp.toPx() }

    // 初始化光标到中心
    LaunchedEffect(containerWidthPx, containerHeightPx) {
        if (containerWidthPx > 0 && containerHeightPx > 0 && !cursorInitialized) {
            cursorX = containerWidthPx / 2f
            cursorY = containerHeightPx / 2f
            cursorInitialized = true
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // 清理 WebView
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching {
                    wv.removeJavascriptInterface("Android")
                    wv.stopLoading()
                    wv.destroy()
                }
            }
        }
    }

    fun clampCursor() {
        cursorX = cursorX.coerceIn(0f, containerWidthPx)
        cursorY = cursorY.coerceIn(0f, containerHeightPx)
    }

    fun moveCursor(dx: Float, dy: Float, fast: Boolean) {
        val step = if (fast) fastStep else baseStep
        cursorX += dx * step
        cursorY += dy * step
        clampCursor()
    }

    fun dispatchClickToWebView() {
        val wv = webViewRef ?: return
        val now = SystemClock.uptimeMillis()
        val x = cursorX
        val y = cursorY
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
        wv.dispatchTouchEvent(down)
        wv.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        logger.debug { "Dispatched click at ($x, $y)" }
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

                if (!isDown) return@onPreviewKeyEvent false

                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        moveCursor(0f, -1f, isLongPress); true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        moveCursor(0f, 1f, isLongPress); true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveCursor(-1f, 0f, isLongPress); true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveCursor(1f, 0f, isLongPress); true
                    }

                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        dispatchClickToWebView(); true
                    }

                    KeyEvent.KEYCODE_BACK -> {
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
                    .clip(RoundedCornerShape(8.dp)),
                factory = { ctx ->
                    val webView = WebView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        webChromeClient = WebChromeClient()

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
                                    onResult(
                                        GeetestTvResult(
                                            challenge = c,
                                            validate = v,
                                            seccode = s
                                        )
                                    )
                                }

                                @JavascriptInterface
                                fun onStatusUpdate(text: String?) {
                                    text?.let { statusText = it }
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

                        webViewRef = this
                    }

                    val overlay = CrosshairOverlayView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        overlayRef = this
                    }

                    FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        addView(webView)
                        addView(overlay)
                    }
                },
                update = { frame ->
                    val wv = webViewRef ?: return@AndroidView
                    wv.post {
                        if (wv.width > 0 && wv.height > 0) {
                            containerWidthPx = wv.width.toFloat()
                            containerHeightPx = wv.height.toFloat()
                        }
                    }
                    // 更新覆盖层光标位置
                    overlayRef?.setCursorPosition(cursorX, cursorY)
                },
            )
        }

        // ---- 操作提示 ----
        Text(
            text = "方向键移动光标 ｜ 确认键点击 ｜ 返回键取消",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 原生 View 十字光标覆盖层，渲染在 WebView 之上。
 */
private class CrosshairOverlayView(context: Context) : View(context) {
    private var cx = 0f
    private var cy = 0f

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

    fun setCursorPosition(x: Float, y: Float) {
        cx = x
        cy = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cx == 0f && cy == 0f) return

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
    /* 让极验验证面板自适应容器 */
    .geetest_panel { position: relative !important; }
    .geetest_panel_box { position: relative !important; }
    .geetest_wind { position: relative !important; }
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
          notify('请使用方向键移动光标，确认键点击');
          // 自动弹出验证
          captchaObj.verify();
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
        });
        captchaObj.onClose(function() {
          // 极验面板关闭后重新弹出，防止误触关闭
          notify('验证已关闭，正在重新打开…');
          // setTimeout(function() { captchaObj.verify(); }, 500);
        });
      });
    })();
  </script>
</body>
</html>
    """.trimIndent()
}
