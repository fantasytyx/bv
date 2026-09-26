package dev.aaa1115910.bv.macrobenchmark

import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 切 Tab + 滚动列表的掉帧基线，按键序列与 baseline profile 的 CUJ 保持一致。
 *
 * 注意：`NavSwitchMode.Confirm` 模式下方向键只移动高亮，**必须按确认键**才真正切换内容，
 * 切换后还有约 600ms 的 tabMoved 门控会吞掉 DOWN 键，所以要等够时间再进列表。
 * 按键次数经设备实测校准：8 次 DOWN 之后需要 10 次 UP 才能回到 Tab 行。
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class HomeNavigationBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** 只跑 baseline profile 的 AOT 编译。 */
    @Test
    fun homeTabSwitchAndScrollJankWithProfile() =
        measureJank(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    /** 同一交互序列但不做 AOT 编译，用于对比 profile 对运行期的效果。 */
    @Test
    fun homeTabSwitchAndScrollJankNoProfile() = measureJank(CompilationMode.None())

    private fun measureJank(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = BuildConfig.TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            // 这台 TV 盒子不产出 frame timeline 数据（FrameTimingMetric 只给 frameCount），
            // 用 atrace 的 Choreographer#doFrame 作掉帧代理：Max = 最差一帧，Sum = 该轮帧耗时总量
            TraceSectionMetric("Choreographer#doFrame", TraceSectionMetric.Mode.Max),
            TraceSectionMetric("Choreographer#doFrame", TraceSectionMetric.Mode.Sum),
        ),
        compilationMode = compilationMode,
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(BuildConfig.TARGET_PACKAGE)), VISIBLE_TIMEOUT_MS)
        SystemClock.sleep(HOME_SETTLE_MS)

        switchTabToRight()
        scrollListAndBack()

        switchTabToLeft()
        scrollListAndBack()
    }

    /** 焦点在 Tab 行时：右移一格 + 确认键切内容。 */
    private fun MacrobenchmarkScope.switchTabToRight() {
        device.pressDPadRight()
        SystemClock.sleep(FOCUS_MOVE_MS)
        device.pressDPadCenter()
        SystemClock.sleep(TAB_SWITCH_MS)
    }

    /** 左移一格 + 确认键切回（左移只能按一次，再按会打开侧边栏）。 */
    private fun MacrobenchmarkScope.switchTabToLeft() {
        device.pressDPadLeft()
        SystemClock.sleep(FOCUS_MOVE_MS)
        device.pressDPadCenter()
        SystemClock.sleep(TAB_SWITCH_MS)
    }

    /** 进入内容列表、向下滚动，再退回 Tab 行。 */
    private fun MacrobenchmarkScope.scrollListAndBack() {
        repeat(SCROLL_STEPS) {
            device.pressDPadDown()
            SystemClock.sleep(SCROLL_STEP_MS)
        }
        repeat(SCROLL_STEPS + 3) {
            device.pressDPadUp()
            SystemClock.sleep(FOCUS_MOVE_MS)
        }
    }

    private companion object {
        const val ITERATIONS = 5
        const val SCROLL_STEPS = 8
        const val VISIBLE_TIMEOUT_MS = 10_000L
        const val HOME_SETTLE_MS = 3_000L
        const val FOCUS_MOVE_MS = 150L
        const val SCROLL_STEP_MS = 150L
        const val TAB_SWITCH_MS = 2_500L
    }
}
