@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.dsh.miuixbrowser.player

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 原生播放页（阶段一）：ExoPlayer + miuix 主题的最简界面，用来验证播放内核。
 * 悬浮窗形态、画中画、后台播放在后续阶段复用同一个 [MafariPlayer]。
 *
 * 入口：[PlayerRequest.toIntent]（脚本宿主的 `play` 操作也走这里）。
 */
class NativePlayerActivity : ComponentActivity() {

    private var player: MafariPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = PlayerRequest.from(intent)
        if (req == null) {
            finish()
            return
        }
        // 全屏沉浸：藏系统栏，边缘滑动临时唤出；方向跟随传感器（视频比例变化时用户自己转）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        val p = MafariPlayer(this, req)
        player = p
        setContent {
            // 播放页始终是深色：控件叠在视频上，浅色主题下反而看不清
            val themeController = remember { ThemeController(ColorSchemeMode.Dark) }
            MiuixTheme(controller = themeController) {
                PlayerScreen(p, title = req.title.ifBlank { req.url }, onBack = { finish() })
            }
        }
    }

    override fun onStop() {
        // 阶段一：离开界面就暂停；后台播放 / 画中画在后面的阶段接入
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
private val Scrim = Brush.verticalGradient(listOf(Color(0xB0000000), Color.Transparent))
private val ScrimBottom = Brush.verticalGradient(listOf(Color.Transparent, Color(0xB0000000)))

@Composable
private fun PlayerScreen(player: MafariPlayer, title: String, onBack: () -> Unit) {
    var controls by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    // 0 = 无；1 = 倍速；2 = 清晰度
    var panel by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf<String?>(null) }

    // 进度刷新：即使暂停也刷（拖动后的位置要立刻反映）
    LaunchedEffect(player) {
        while (true) {
            player.refresh()
            delay(250)
        }
    }
    // 播放中且没在操作时 4 秒后自动收起控件
    LaunchedEffect(controls, player.isPlaying, dragging, panel) {
        if (controls && player.isPlaying && !dragging && panel == 0) {
            delay(4000)
            controls = false
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(700)
            hint = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(player) {
                detectTapGestures(
                    onTap = {
                        if (panel != 0) panel = 0 else controls = !controls
                    },
                    onDoubleTap = { o ->
                        val w = size.width
                        when {
                            o.x < w / 3f -> { player.seekBy(-10_000); hint = tr("-10 秒") }
                            o.x > w * 2f / 3f -> { player.seekBy(10_000); hint = tr("+10 秒") }
                            else -> player.toggle()
                        }
                    },
                )
            },
    ) {
        val ratio = if (player.videoWidth > 0 && player.videoHeight > 0) player.videoWidth.toFloat() / player.videoHeight else 0f
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { player.attach(it) } },
            onRelease = { player.detach(it) },
            modifier = if (ratio > 0f) Modifier.align(Alignment.Center).aspectRatio(ratio) else Modifier.fillMaxSize(),
        )

        if (player.buffering && player.error == null) {
            Label(tr("缓冲中…"), Modifier.align(Alignment.Center))
        }
        hint?.let { Label(it, Modifier.align(Alignment.Center)) }

        player.error?.let { msg ->
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = msg, color = Color.White, fontSize = 15.sp)
                Box(Modifier.height(12.dp))
                Chip(tr("重试"), selected = false) { player.retry() }
            }
        }

        if (controls) {
            // 顶栏
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Scrim)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(text = "‹", color = Color.White, fontSize = 28.sp)
                }
                Text(text = title, color = Color.White, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
            }

            // 底栏
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(ScrimBottom)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (panel == 1) {
                    ChipRow {
                        SPEEDS.forEach { s ->
                            Chip(speedLabel(s), selected = player.speed == s) { player.setSpeed(s); panel = 0 }
                        }
                    }
                } else if (panel == 2) {
                    ChipRow {
                        Chip(tr("自动"), selected = player.selectedQuality == null) { player.selectQuality(null); panel = 0 }
                        player.qualities.forEach { q ->
                            Chip(q.label, selected = player.selectedQuality === q) { player.selectQuality(q); panel = 0 }
                        }
                    }
                }
                SeekBar(
                    position = player.positionMs,
                    duration = player.durationMs,
                    buffered = player.bufferedMs,
                    enabled = !player.isLive && player.durationMs > 0,
                    onDragging = { dragging = it },
                    onSeek = { player.seekTo(it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clickable { player.toggle() }.padding(end = 14.dp, top = 4.dp, bottom = 4.dp)) {
                        Text(text = if (player.isPlaying) "❚❚" else "▶", color = Color.White, fontSize = 20.sp)
                    }
                    Text(
                        text = if (player.isLive) tr("直播") else "${fmt(player.positionMs)} / ${fmt(player.durationMs)}",
                        color = Color.White, fontSize = 13.sp,
                    )
                    Box(Modifier.weight(1f))
                    Chip(tr("倍速 {0}", speedLabel(player.speed)), selected = false) { panel = if (panel == 1) 0 else 1 }
                    if (player.qualities.size > 1) {
                        Box(Modifier.padding(start = 8.dp))
                        Chip(player.selectedQuality?.label ?: tr("自动"), selected = false) { panel = if (panel == 2) 0 else 2 }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text = text, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color(0xFF3482FF) else Color(0x40FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) { Text(text = text, color = Color.White, fontSize = 13.sp) }
}

@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    buffered: Long,
    enabled: Boolean,
    onDragging: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufFrac = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(enabled, duration) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { o -> onSeek((o.x / size.width * duration).toLong()) })
            }
            .pointerInput(enabled, duration) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f); onDragging(true) },
                    onDragEnd = {
                        val f = drag
                        drag = null
                        onDragging(false)
                        if (f != null) onSeek((f * duration).toLong())
                    },
                    onDragCancel = { drag = null; onDragging(false) },
                    onHorizontalDrag = { c, _ -> drag = (c.position.x / size.width).coerceIn(0f, 1f) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val stroke = 4.dp.toPx()
            line(Color(0x40FFFFFF), 0f, size.width, cy, stroke)
            line(Color(0x66FFFFFF), 0f, size.width * bufFrac, cy, stroke)
            line(Color.White, 0f, size.width * frac, cy, stroke)
            if (enabled) drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(size.width * frac, cy))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.line(c: Color, x0: Float, x1: Float, y: Float, w: Float) {
    if (x1 > x0) drawLine(c, Offset(x0, y), Offset(x1, y), strokeWidth = w, cap = StrokeCap.Round)
}

private fun speedLabel(s: Float): String = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

private fun fmt(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
