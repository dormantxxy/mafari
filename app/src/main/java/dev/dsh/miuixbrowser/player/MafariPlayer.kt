@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.dsh.miuixbrowser.player

import android.content.Context
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.dsh.miuixbrowser.i18n.tr

/** 清晰度条目：[group] / [index] 对应 ExoPlayer 轨道，选中时用 TrackSelectionOverride。 */
class Quality(val label: String, val height: Int, val bitrate: Int, internal val group: Tracks.Group, internal val index: Int)

/**
 * 原生播放器核心：ExoPlayer + 带请求头的网络层 + 伪装图片头裁剪。
 * 界面状态用 Compose State 暴露，必须在主线程使用（ExoPlayer 默认的应用线程）。
 * 这一层不碰任何界面类，后面悬浮窗 / 画中画 / 后台播放都复用同一个实例。
 */
class MafariPlayer(context: Context, val request: PlayerRequest) {

    val exo: ExoPlayer

    var isPlaying by mutableStateOf(false); private set
    var buffering by mutableStateOf(true); private set
    var positionMs by mutableLongStateOf(0); private set
    var durationMs by mutableLongStateOf(0); private set
    var bufferedMs by mutableLongStateOf(0); private set
    var speed by mutableFloatStateOf(1f); private set
    var videoWidth by mutableIntStateOf(0); private set
    var videoHeight by mutableIntStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set
    var qualities by mutableStateOf<List<Quality>>(emptyList()); private set

    /** 手动选中的清晰度；null = 自动（ExoPlayer 按带宽选） */
    var selectedQuality by mutableStateOf<Quality?>(null); private set

    /** 直播流：时长未知 / 动态窗口。进度条按这个决定是否可拖 */
    var isLive by mutableStateOf(false); private set

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@MafariPlayer.isPlaying = isPlaying
        }

        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) error = null
            refresh()
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            videoWidth = size.width
            // 带像素宽高比（非方形像素）时折算成显示用的宽度
            if (size.pixelWidthHeightRatio != 1f && size.pixelWidthHeightRatio > 0f) {
                videoWidth = (size.width * size.pixelWidthHeightRatio).toInt()
            }
            videoHeight = size.height
        }

        override fun onTracksChanged(tracks: Tracks) {
            val list = ArrayList<Quality>()
            for (g in tracks.groups) {
                if (g.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val f = g.getTrackFormat(i)
                    val h = f.height.takeIf { it > 0 } ?: continue
                    val br = if (f.bitrate > 0) f.bitrate else 0
                    list += Quality(label = "${h}p", height = h, bitrate = br, group = g, index = i)
                }
            }
            // 同高度只留码率最高的一条，高到低排列
            qualities = list.groupBy { it.height }.map { (_, v) -> v.maxBy { it.bitrate } }.sortedByDescending { it.height }
        }

        override fun onPlayerError(e: PlaybackException) {
            error = describe(e)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = refresh()
    }

    init {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(request.headers())
            .apply { if (request.ua.isNotBlank()) setUserAgent(request.ua) }
        // 只有 http(s) 走裁剪包装；file / content 等由 DefaultDataSource 处理
        val dataSource = DefaultDataSource.Factory(context, StripImageHeaderDataSource.Factory(http))
        exo = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource))
            // 比默认更早起播：网页视频场景更在意首帧速度，而不是缓冲余量
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 60_000, 1_000, 2_000)
                    .build(),
            )
            .build()
        exo.addListener(listener)
        val item = MediaItem.Builder().setUri(request.url).apply {
            if (request.isHls()) setMimeType(MimeTypes.APPLICATION_M3U8)
            if (request.title.isNotBlank()) {
                setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(request.title).build())
            }
        }.build()
        exo.setMediaItem(item, request.startMs.coerceAtLeast(0))
        exo.prepare()
        exo.playWhenReady = true
    }

    fun attach(view: SurfaceView) = exo.setVideoSurfaceView(view)
    fun detach(view: SurfaceView) = exo.clearVideoSurfaceView(view)

    /** 周期性刷新进度（界面每 250ms 调一次；不在播放时也要刷新拖动后的位置） */
    fun refresh() {
        positionMs = exo.currentPosition.coerceAtLeast(0)
        bufferedMs = exo.bufferedPosition.coerceAtLeast(0)
        val d = exo.duration
        durationMs = if (d == C.TIME_UNSET) 0 else d
        isLive = exo.isCurrentMediaItemLive || (exo.isCurrentMediaItemDynamic && d == C.TIME_UNSET)
    }

    fun toggle() { if (exo.isPlaying) exo.pause() else { if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0); exo.play() } }
    fun play() = exo.play()
    fun pause() = exo.pause()

    fun seekTo(ms: Long) { exo.seekTo(ms.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)); refresh() }
    fun seekBy(deltaMs: Long) = seekTo(exo.currentPosition + deltaMs)

    fun setSpeed(s: Float) {
        exo.playbackParameters = PlaybackParameters(s)
        speed = s
    }

    /** null = 自动 */
    fun selectQuality(q: Quality?) {
        val b = exo.trackSelectionParameters.buildUpon()
        if (q == null) {
            b.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            b.setOverrideForType(TrackSelectionOverride(q.group.mediaTrackGroup, q.index))
        }
        exo.trackSelectionParameters = b.build()
        selectedQuality = q
    }

    fun retry() {
        error = null
        exo.prepare()
        exo.play()
    }

    fun release() {
        exo.removeListener(listener)
        exo.release()
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val code = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            when (code) {
                403, 401 -> tr("服务器拒绝访问（{0}），可能需要登录或带防盗链信息", code)
                404 -> tr("资源不存在（404）")
                null -> tr("服务器返回了错误状态")
                else -> tr("服务器返回错误（{0}）", code)
            }
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> tr("网络连接失败")
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> tr("无法识别的视频格式")
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> tr("设备不支持这种编码")
        else -> tr("播放失败：{0}", e.errorCodeName)
    }
}
