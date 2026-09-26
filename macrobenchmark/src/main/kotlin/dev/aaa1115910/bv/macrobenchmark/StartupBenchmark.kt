package dev.aaa1115910.bv.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冷启动基线。`compilationNone` 与 `compilationBaselineProfile` 的差值就是 baseline profile 的实际收益。
 *
 * 只需要一台 API 24+ 设备（建议 API 34+，避免每次迭代重装 APK）：
 * `./gradlew :macrobenchmark:connectedDefaultBenchmark`
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** 没有任何 AOT 编译，最接近首次安装后的状态。 */
    @Test
    fun startupCompilationNone() = startup(CompilationMode.None())

    /** 只跑 baseline profile 的 AOT 编译。 */
    @Test
    fun startupCompilationBaselineProfile() =
        startup(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun startup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = BuildConfig.TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    private companion object {
        const val ITERATIONS = 5
    }
}
