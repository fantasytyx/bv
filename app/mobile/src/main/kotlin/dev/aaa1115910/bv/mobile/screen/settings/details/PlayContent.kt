package dev.aaa1115910.bv.mobile.screen.settings.details

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.aaa1115910.bv.mobile.component.preferences.items.radioPreference
import dev.aaa1115910.bv.mobile.component.preferences.items.switchPreference
import dev.aaa1115910.bv.mobile.component.preferences.preferenceGroups
import dev.aaa1115910.bv.mobile.theme.BVMobileTheme
import dev.aaa1115910.bv.player.entity.Audio
import dev.aaa1115910.bv.player.entity.Resolution
import dev.aaa1115910.bv.player.entity.VideoCodec
import dev.aaa1115910.bv.util.PrefKeys

@Composable
fun PlayContent(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp)
    ) {
        preferenceGroups(
            "画面" to {
                radioPreference(
                    title = "默认画质",
                    prefReq = PrefKeys.prefDefaultQualityRequest,
                    values = Resolution.entries.associate { it.code to it.getDisplayName(context) }
                        .toSortedMap { a, b -> a.compareTo(b) }
                )
                radioPreference(
                    title = "默认视频编码",
                    prefReq = PrefKeys.prefDefaultVideoCodecRequest,
                    values = VideoCodec.entries.associate { it.codecId to it.getDisplayName(context) }
                        .toSortedMap { a, b -> a.compareTo(b) }
                )
            },
            "音频" to {
                radioPreference(
                    title = "默认音频",
                    prefReq = PrefKeys.prefDefaultAudioRequest,
                    values = Audio.entries.associate { it.code to it.getDisplayName(context) }
                        .toSortedMap { a, b -> a.compareTo(b) }
                )
            },
            "播放" to {
                switchPreference(
                    title = "频繁缓冲时自动切换线路",
                    summary = "同一线路 90 秒内缓冲 3 次、或单次缓冲超过 12 秒时，自动换下一个播放地址；" +
                            "仅在拿到多个地址时有效。起播与快进/快退引起的缓冲不计",
                    prefReq = PrefKeys.prefPlayerSwitchCdnOnBufferingRequest,
                    onCheckedChange = { true }
                )
            }
        )
    }
}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PlayContentPreview() {
    BVMobileTheme {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            PlayContent(
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
