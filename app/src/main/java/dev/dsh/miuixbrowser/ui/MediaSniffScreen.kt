package dev.dsh.miuixbrowser.ui

import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.player.PlayerPrefs
import dev.dsh.miuixbrowser.player.speedLabel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置 → 高级 → 媒体嗅探与播放（两个内核共用）：
 *  - 总开关：一键停用嗅探、原生悬浮播放、自动画中画、脚本宿主能力和 M3U8 媒体下载器脚本；
 *  - 嗅探媒体播放 / 退出自动画中画 / 自动悬浮播放（嗅探到视频直接用原生悬浮播放器打开）；
 *  - 播放器：渲染方式（TextureView / SurfaceView）、默认倍速、长按倍速、后台播放；
 *  - 下载：HLS 转 MP4（原来在下载页里）。
 * 「网页增强」（WebView）关闭时本页功能同样不运行，详见 [dev.dsh.miuixbrowser.sniff.MediaFeatures]。
 *
 * [onMasterChange]：总开关变化后的联动（启停推荐的媒体脚本）；开关值本身已由本页写入 [AppSettings]。
 */
@Composable
fun MediaSniffScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onMasterChange: (Boolean) -> Unit = {},
    blurEnabled: Boolean = true,
) {
    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    // 子开关是否可操作：总开关开，且（WebView 下）网页增强开
    val active = settings.mediaActive
    val enhancementsOff = !settings.geckoMode && !settings.pageEnhancements

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = tr("媒体嗅探与播放"),
                    backdrop = backdrop,
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    backButton = {
                        LiquidGlassBackButton(
                            backdrop = if (glass) liquidBackdrop else null,
                            surfaceVisible = topScrolled,
                            onClick = onBack,
                        )
                    },
                )
            },
        ) { innerPadding ->
            val topInset = innerPadding.calculateTopPadding()
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                        .kyantLayerBackdrop(liquidBackdrop)
                        .scrollEndHaptic()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .pageBackground()
                        .verticalScroll(rememberScrollState())
                        .padding(top = topInset)
                        .padding(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                ) {
                    SmallTitle(text = tr("总开关"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppSwitchPreference(
                            title = tr("媒体嗅探与播放"),
                            summary = tr("一键停用：嗅探、原生悬浮播放、自动画中画、自动悬浮播放及相关脚本；关闭后不再拦截 / 弹窗，刷新页面后生效"),
                            checked = settings.mediaMaster,
                            onCheckedChange = {
                                settings.updateMediaMaster(it)
                                onMasterChange(it)
                            },
                        )
                    }
                    if (enhancementsOff) {
                        Text(
                            text = tr("「网页增强」已关闭，本页功能暂不运行。重新打开网页增强后恢复。"),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
                        )
                    }

                    SmallTitle(text = tr("嗅探与播放"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppSwitchPreference(
                            title = tr("嗅探媒体播放"),
                            summary = tr("网页里出现视频（m3u8 / mp4 等）时弹出提示，可用原生播放器打开；刷新页面后生效"),
                            checked = settings.mediaSniff,
                            onCheckedChange = { settings.updateMediaSniff(it) },
                            enabled = active,
                        )
                        AppSwitchPreference(
                            title = tr("退出自动画中画"),
                            summary = tr("全屏播放视频时按 Home / 手势返回桌面，自动进入画中画小窗"),
                            checked = settings.mediaAutoPip,
                            onCheckedChange = { settings.updateMediaAutoPip(it) },
                            enabled = active,
                        )
                        AppSwitchPreference(
                            title = tr("自动悬浮播放"),
                            summary = tr("嗅探到视频后直接用悬浮播放器打开，不再先弹「发现视频」提示；已有播放器开着时不会重复打开"),
                            checked = settings.mediaAutoFloat,
                            onCheckedChange = { settings.updateMediaAutoFloat(it) },
                            enabled = active,
                        )
                    }

                    SmallTitle(text = tr("播放器"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        AppDropdownPreference(
                            backdrop = if (glass) backdrop else null,
                            title = tr("渲染方式"),
                            summary = tr("TextureView 支持玻璃模糊；SurfaceView 更省电、延迟更低，但没有玻璃模糊。下次打开播放器生效"),
                            items = listOf("TextureView", "SurfaceView"),
                            selectedIndex = settings.playerRenderMode,
                            onSelectedIndexChange = { settings.updatePlayerRenderMode(it) },
                        )
                        AppDropdownPreference(
                            backdrop = if (glass) backdrop else null,
                            title = tr("默认倍速"),
                            summary = tr("每次打开播放器按这个速度起播，播放页里仍可临时修改"),
                            items = PlayerPrefs.SPEEDS.map { speedLabel(it) },
                            selectedIndex = PlayerPrefs.SPEEDS.indexOf(settings.playerDefaultSpeed).coerceAtLeast(0),
                            onSelectedIndexChange = { settings.updatePlayerDefaultSpeed(PlayerPrefs.SPEEDS[it]) },
                        )
                        AppDropdownPreference(
                            backdrop = if (glass) backdrop else null,
                            title = tr("长按倍速"),
                            summary = tr("长按播放画面时临时加速，松手恢复"),
                            items = PlayerPrefs.LONG_PRESS_SPEEDS.map { speedLabel(it) },
                            selectedIndex = PlayerPrefs.LONG_PRESS_SPEEDS.indexOf(settings.playerLongPressSpeed).coerceAtLeast(0),
                            onSelectedIndexChange = { settings.updatePlayerLongPressSpeed(PlayerPrefs.LONG_PRESS_SPEEDS[it]) },
                        )
                        AppSwitchPreference(
                            title = tr("后台播放"),
                            summary = tr("切到后台 / 锁屏继续播放，并在通知栏显示控制条；关闭后离开界面就暂停"),
                            checked = settings.playerBgPlay,
                            onCheckedChange = { settings.updatePlayerBgPlay(it) },
                        )
                    }

                    SmallTitle(text = tr("下载"))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        // m3u8 一律走内置下载器，所以这一项不受下载页里「下载器」选择影响；也不受总开关约束（下载页里手动下 m3u8 同样用它）
                        AppSwitchPreference(
                            title = tr("HLS 转 MP4"),
                            summary = tr("m3u8 下载完成后把 TS 转封装成 MP4（不重新编码；编码不支持时保留 TS）"),
                            checked = settings.hlsToMp4,
                            onCheckedChange = { settings.updateHlsToMp4(it) },
                        )
                    }
                }
            }
        }
    }
}
