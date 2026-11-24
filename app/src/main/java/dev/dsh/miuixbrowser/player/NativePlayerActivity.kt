@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.dsh.miuixbrowser.player

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.util.Diag
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 原生播放页：ExoPlayer 内核（[MafariPlayer]）+ 和 float-player.js 一致的 miuix 界面（[PlayerSurface]）。
 *
 * 形态和脚本一致：竖屏时是顶部一张 16:9 圆角卡片（✕ 关闭），点右下角全屏钮铺满屏幕并转横屏，
 * 全屏里左上角 ‹ 退出。
 *
 * 阶段三（同一个 [MafariPlayer] 实例，界面 / 画中画 / 后台共用，切换不重建播放器）：
 *  - 画中画：点右下角 PiP 按钮进入；播放中按 Home / 手势返回桌面自动进入；小窗操作栏 后退 10 秒 / 播放·暂停 / 快进 10 秒。
 *    小窗里只剩画面；点 ✕ 关闭小窗 = 结束播放。
 *  - 后台播放（菜单里可关，默认开）：不在画中画时切到后台 / 锁屏继续播放，由 [PlayerService] 提供前台服务和媒体通知；
 *    关掉时离开界面就暂停（原来的行为）。
 *  - 下载 / 复制链接 / 视频源切换：菜单里。
 *
 * 入口：[PlayerRequest.toIntent]（脚本宿主的 `play` 操作也走这里）。
 */
class NativePlayerActivity : ComponentActivity() {

    private var player: MafariPlayer? = null
    private var fullscreen by mutableStateOf(false)
    private var inPip by mutableStateOf(false)
    private var bgPlay by mutableStateOf(true)
    private var pipAt = 0L // 最近一次进入画中画的时间，用来分辨「刚进小窗的过渡性 onStop」和「用户点 ✕ 关了小窗」
    private var pipReceiver: BroadcastReceiver? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 没授权服务照样跑，只是看不到通知 */ }

    /** ExoPlayer 状态变了就刷新画中画参数：操作栏的播放 / 暂停图标、宽高比、「播放中才自动进入」 */
    private val pipSync = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePip()
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = updatePip()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = PlayerRequest.from(intent)
        if (req == null) {
            finish()
            return
        }
        bgPlay = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_BG_PLAY, true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyFullscreen(false)

        val p = MafariPlayer(this, req)
        player = p
        p.exo.addListener(pipSync)
        PlayerService.closeCallback = { finish() }
        registerPipReceiver()
        updatePip()
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
        val chrome = if (fullscreen || inPip) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)
        }
        val surface = if (fullscreen || inPip) {
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
                onPip = if (pipSupported()) ({ enterPip() }) else null,
                onDownload = { download(p) },
                onCopyLink = { copyLink(p) },
                pip = inPip,
                bgPlay = bgPlay,
                onBgPlayChange = { changeBgPlay(it) },
            )
            if (!fullscreen && !inPip) {
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

    // ------------------------------------------------------------------ 下载 / 复制链接

    /** 交给 App 下载器：普通文件按设置里选的下载器；m3u8 由 App 自己取清单、下分片、合并（前台服务，离开页面也不中断）。 */
    private fun download(p: MafariPlayer) {
        val r = p.currentRequest
        val ua = r.ua.takeIf { it.isNotBlank() }
        val referer = r.referer.takeIf { it.isNotBlank() }
        val name = r.downloadName()
        val ctx = applicationContext
        if (r.isHls()) {
            Downloader.startHls(ctx, r.url, ua, name, referer, true, r.gecko)
        } else {
            Downloader.start(ctx, r.url, ua, name, null, null, referer, true, r.gecko)
        }
    }

    private fun copyLink(p: MafariPlayer) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("video", p.currentRequest.url))
    }

    // ------------------------------------------------------------------ 画中画

    private fun pipSupported(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipAction(code: Int, icon: Int, title: String, act: String) = RemoteAction(
        Icon.createWithResource(this, icon), title, title,
        PendingIntent.getBroadcast(
            this, code, Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_ACT, act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    private fun pipParams(): PictureInPictureParams {
        val p = player
        val w = p?.videoWidth ?: 0
        val h = p?.videoHeight ?: 0
        // 系统只接受 1:2.39 ~ 2.39:1；尺寸未知时先按 16:9
        val ratio = if (w > 0 && h > 0) (w.toDouble() / h).coerceIn(0.42, 2.39) else 16.0 / 9.0
        val playing = p?.isPlaying == true
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(
                listOf(
                    pipAction(1, android.R.drawable.ic_media_rew, tr("后退 10 秒"), "back"),
                    if (playing) pipAction(2, android.R.drawable.ic_media_pause, tr("暂停"), "pause")
                    else pipAction(3, android.R.drawable.ic_media_play, tr("播放"), "play"),
                    pipAction(4, android.R.drawable.ic_media_ff, tr("快进 10 秒"), "fwd"),
                ),
            )
            // 播放中按 Home / 手势返回桌面，系统直接进画中画（比 onUserLeaveHint 更稳，没有切换闪烁）
            .setAutoEnterEnabled(playing && pipSupported())
            .setSeamlessResizeEnabled(false)
            .build()
    }

    private fun updatePip() {
        if (isDestroyed || isFinishing || !pipSupported()) return
        runCatching { setPictureInPictureParams(pipParams()) }.onFailure { Diag.log("NativePlayer: setPictureInPictureParams $it") }
    }

    private fun enterPip() {
        if (!pipSupported() || isInPictureInPictureMode) return
        pipAt = SystemClock.uptimeMillis()
        runCatching { enterPictureInPictureMode(pipParams()) }.onFailure { Diag.log("NativePlayer: enterPictureInPictureMode $it") }
    }

    /** 小窗操作栏的按钮：经广播回到这里，驱动同一个播放器 */
    private fun registerPipReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val p = player ?: return
                when (i.getStringExtra(EXTRA_ACT)) {
                    "back" -> p.seekBy(-SKIP_MS)
                    "fwd" -> p.seekBy(SKIP_MS)
                    "play" -> p.play()
                    "pause" -> p.pause()
                }
            }
        }
        ContextCompat.registerReceiver(this, r, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        pipReceiver = r
    }

    /** 按 Home / 切任务：系统没替我们自动进画中画时的兜底（部分 ROM 不认 autoEnter） */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && !isInPictureInPictureMode) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) pipAt = SystemClock.uptimeMillis()
    }

    // ------------------------------------------------------------------ 后台播放

    private fun changeBgPlay(on: Boolean) {
        bgPlay = on
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_BG_PLAY, on).apply()
        if (on) startBgService() else PlayerService.stop(this)
    }

    /** 界面可见时启动前台服务（后台启动前台服务会被系统拦）；Android 13+ 还要通知权限才看得到通知栏的控制条 */
    private fun startBgService() {
        val p = player ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PlayerService.start(this, p)
    }

    override fun onStart() {
        super.onStart()
        if (bgPlay) startBgService()
    }

    override fun onStop() {
        super.onStop()
        val p = player ?: return
        if (isFinishing) return
        if (isInPictureInPictureMode || inPip) {
            // 刚进小窗的 700ms 内个别系统会先回调一次 onStop：不是退后台，别暂停。
            // 之后再来的 onStop = 用户点了小窗的 ✕，结束播放。
            if (SystemClock.uptimeMillis() - pipAt > 700) {
                p.pause()
                finish()
            }
            return
        }
        // 不在小窗：后台播放开着就继续（服务保活 + 通知栏控制），关着就暂停
        if (!bgPlay) p.pause()
    }

    override fun onDestroy() {
        PlayerService.stop(this)
        PlayerService.closeCallback = null
        PlayerService.current = null
        pipReceiver?.let { runCatching { unregisterReceiver(it) } }
        pipReceiver = null
        player?.exo?.removeListener(pipSync)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "mafari_player"
        private const val KEY_BG_PLAY = "bg_play"
        private const val ACTION_PIP = "dev.dsh.miuixbrowser.player.PIP_ACT"
        private const val EXTRA_ACT = "a"
        private const val SKIP_MS = 10_000L
    }
}
