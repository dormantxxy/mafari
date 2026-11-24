@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.dsh.miuixbrowser.player

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 原生播放页：ExoPlayer 内核（[MafariPlayer]）+ 和 float-player.js 一致的 miuix 界面（[PlayerSurface]）。
 *
 * 形态和脚本一致：竖屏时是顶部一张 16:9 圆角卡片（✕ 关闭），点右下角全屏钮铺满屏幕并转横屏，
 * 全屏里左上角 ‹ 退出。后台播放 / 画中画在阶段三接入同一个 [MafariPlayer]。
 *
 * 入口：[PlayerRequest.toIntent]（脚本宿主的 `play` 操作也走这里）。
 */
class NativePlayerActivity : ComponentActivity() {

    private var player: MafariPlayer? = null
    private var fullscreen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = PlayerRequest.from(intent)
        if (req == null) {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyFullscreen(false)

        val p = MafariPlayer(this, req)
        player = p
        setContent {
            // 播放页始终是深色：控件叠在视频上，浅色主题下反而看不清
            val themeController = remember { ThemeController(ColorSchemeMode.Dark) }
            MiuixTheme(controller = themeController) {
                BackHandler(enabled = fullscreen) { applyFullscreen(false) }
                PlayerPage(p, title = req.title.ifBlank { req.url })
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun PlayerPage(p: MafariPlayer, title: String) {
        // 只有一处 PlayerSurface 调用，卡片 <-> 全屏切换时界面状态（弹层、控件显隐）不会丢
        val chrome = if (fullscreen) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)
        }
        val surface = if (fullscreen) {
            Modifier.fillMaxSize()
        } else {
            val shape = RoundedCornerShape(18.dp)
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .border(1.dp, Color(0x1FFFFFFF), shape)
        }
        Column(Modifier.fillMaxSize().background(Color.Black).then(chrome)) {
            PlayerSurface(
                player = p,
                fullscreen = fullscreen,
                onClose = { finish() },
                onToggleFullscreen = { applyFullscreen(!fullscreen) },
                modifier = surface,
            )
            if (!fullscreen) {
                Text(
                    text = title,
                    color = Color(0xCCFFFFFF),
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                )
            }
        }
    }

    /**
     * 全屏：藏系统栏（边缘滑动临时唤出）+ 横屏（视频是横向或尚未知尺寸时）；
     * 退出：显示系统栏 + 锁回竖屏。方向由按钮驱动，和脚本的全屏按钮一致。
     */
    private fun applyFullscreen(on: Boolean) {
        fullscreen = on
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            val p = player
            val portraitVideo = p != null && p.videoWidth > 0 && p.videoHeight > p.videoWidth
            requestedOrientation = if (portraitVideo) {
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
    }

    override fun onStop() {
        // 阶段二仍然是离开界面就暂停；后台播放 / 画中画在阶段三接入
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
