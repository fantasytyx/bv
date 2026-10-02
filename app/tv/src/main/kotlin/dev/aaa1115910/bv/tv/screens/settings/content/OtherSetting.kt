package dev.aaa1115910.bv.tv.screens.settings.content

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.aaa1115910.biliapi.repositories.LoginRepository
import dev.aaa1115910.bv.BuildConfig
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.tv.component.GeetestInputMode
import dev.aaa1115910.bv.tv.component.GeetestTvVerifyDialog
import dev.aaa1115910.bv.tv.component.settings.SettingListItem
import dev.aaa1115910.bv.tv.component.settings.SettingSwitchListItem
import dev.aaa1115910.bv.tv.activities.settings.LogsActivity
import dev.aaa1115910.bv.tv.screens.settings.SettingsMenuNavItem
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun OtherSetting(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var showFps by remember { mutableStateOf(Prefs.showFps) }
    var updateAlpha by remember { mutableStateOf(Prefs.updateAlpha) }

    val loginRepository: LoginRepository = koinInject()
    val scope = rememberCoroutineScope()
    // 调试用：绕过风控链路拿真实 gt/challenge 来观察极验弹窗交互
    var geetestTest by remember { mutableStateOf<GeetestTestParams?>(null) }

    fun startGeetestTest(inputMode: GeetestInputMode) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { loginRepository.getCaptcha() }
            }.onSuccess {
                geetestTest = GeetestTestParams(it.gt, it.challenge, inputMode)
            }.onFailure {
                "获取极验参数失败：${it.message}".toast(context)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = SettingsMenuNavItem.Other.getDisplayName(context),
            style = MaterialTheme.typography.displaySmall
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingSwitchListItem(
                    title = stringResource(R.string.settings_other_fps_title),
                    supportText = stringResource(R.string.settings_other_fps_text),
                    checked = showFps,
                    onCheckedChange = {
                        showFps = it
                        Prefs.showFps = it
                    }
                )
            }
            item {
                SettingSwitchListItem(
                    title = stringResource(R.string.settings_other_alpha_title),
                    supportText = stringResource(R.string.settings_other_alpha_text),
                    checked = updateAlpha,
                    onCheckedChange = {
                        updateAlpha = it
                        Prefs.updateAlpha = it
                    }
                )
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_create_logs_title),
                    supportText = stringResource(R.string.settings_create_logs_text),
                    onClick = {
                        context.startActivity(Intent(context, LogsActivity::class.java))
                    }
                )
            }
            if (BuildConfig.DEBUG) {
                item {
                    SettingListItem(
                        title = stringResource(R.string.settings_crash_test_title),
                        supportText = stringResource(R.string.settings_crash_test_text),
                        onClick = {
                            throw Exception("Boom!")
                        }
                    )
                }
                item {
                    SettingListItem(
                        title = "测试极验验证（点击）",
                        supportText = "方向键移动光标，确认键点击；结果不会提交",
                        onClick = { startGeetestTest(GeetestInputMode.Click) }
                    )
                }
                item {
                    SettingListItem(
                        title = "测试极验验证（滑块）",
                        supportText = "左右键拖动滑块，确认键提交；结果不会提交",
                        onClick = { startGeetestTest(GeetestInputMode.Slider) }
                    )
                }
            }
        }
    }

    geetestTest?.let { params ->
        GeetestTvVerifyDialog(
            gt = params.gt,
            challenge = params.challenge,
            inputMode = params.inputMode,
            onResult = {
                "极验验证成功（调试，未提交）".toast(context)
                geetestTest = null
            },
            onDismiss = { geetestTest = null },
        )
    }
}

private data class GeetestTestParams(
    val gt: String,
    val challenge: String,
    val inputMode: GeetestInputMode,
)