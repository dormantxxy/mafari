@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.dsh.miuixbrowser.player

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
    var muted by mutableStateOf(false); private set

    /** 播放器自身音量 0..1（ExoPlayer 的 volume，叠在系统媒体音量之上，不改系统音量） */
    var volume by mutableFloatStateOf(1f); private set
    var videoWidth by mutableIntStateOf(0); private set
    var videoHeight by mutableIntStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set
    var qualities by mutableStateOf<List<Quality>>(emptyList()); private set

    /** 手动选中的清晰度；null = 自动（ExoPlayer 按带宽选） */
    var selectedQuality by mutableStateOf<Quality?>(null); private set

    /** 直播流：时长未知 / 动态窗口。进度条按这个决定是否可拖 */
    var isLive by mutableStateOf(false); private set

    /** 可切换的视频源（主地址在最前）；只有一个时界面不显示源列表 */
    val sources: List<PlayerSource> = request.allSources()

    /** 当前正在播的源下标 */
    var sourceIndex by mutableIntStateOf(0); private set

    /** 当前播放的请求（地址 / 类型随切源变化，请求头不变）：复制链接 / 下载用它 */
    val currentRequest: PlayerRequest
        get() = sources.getOrNull(sourceIndex)?.let { request.copy(url = it.url, type = it.type) } ?: request

    /** 设置里的「HDR 视频 = 优先 SDR」：开页时读一次 */
    private var preferSdr = false

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
            applySdrPreference(tracks)
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
        preferSdr = PlayerPrefs.hdrMode(context) == PlayerPrefs.HDR_PREFER_SDR
        // 解码方式 / 解码器回退（设置 → 媒体嗅探与播放 → 解码与画质）
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(PlayerPrefs.decoderFallback(context))
            .setMediaCodecSelector(decoderSelector(PlayerPrefs.decoderMode(context)))
        // 缓冲策略：默认「快速起播」（比 ExoPlayer 默认更早起播：网页视频场景更在意首帧速度，而不是缓冲余量）
        val buf = PlayerPrefs.bufferDurationsMs(PlayerPrefs.bufferMode(context))
        exo = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(buf[0], buf[1], buf[2], buf[3])
                    .build(),
            )
            // 音频焦点：来电 / 别的播放器抢焦点时自动暂停（后台播放必须有，否则会和别的音频叠在一起）
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            // 拔耳机 / 蓝牙断开时暂停，免得外放
            .setHandleAudioBecomingNoisy(true)
            // 后台播放（屏幕关闭）时保持 CPU 和 Wi-Fi 唤醒，否则几分钟后系统会掐掉网络
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(listener)
        // 设置里的「默认倍速」：打开就按这个速度起播（播放页里仍可临时改）
        PlayerPrefs.defaultSpeed(context).takeIf { it != 1f }?.let { changeSpeed(it) }
        exo.setMediaItem(buildItem(currentRequest), request.startMs.coerceAtLeast(0))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun buildItem(r: PlayerRequest): MediaItem = MediaItem.Builder().setUri(r.url).apply {
        if (r.isHls()) setMimeType(MimeTypes.APPLICATION_M3U8)
        if (r.title.isNotBlank()) {
            setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(r.title).build())
        }
    }.build()

    /**
     * 「优先 SDR」：同一组视频轨里既有 HDR 又有 SDR 清晰度时，只让 ExoPlayer 在 SDR 轨之间自适应。
     * 只对流里声明了 HDR 的情况有效（HLS 的 VIDEO-RANGE / 编码串里带的色彩信息）；全是 HDR 或没有声明时不处理。
     * 已手动选了清晰度、或这一组已经有覆盖时不动；应用覆盖会再触发一次轨道变化回调，此时已有覆盖，不会循环。
     * 用户在清晰度里选「自动」会清掉覆盖，回调里再按这个偏好重新套上。
     */
    private fun applySdrPreference(tracks: Tracks) {
        if (!preferSdr || selectedQuality != null) return
        val params = exo.trackSelectionParameters
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO || g.length < 2) continue
            if (params.overrides.containsKey(g.mediaTrackGroup)) continue
            val supported = (0 until g.length).filter { g.isTrackSupported(it) }
            val sdr = supported.filter { !isHdr(g.getTrackFormat(it)) }
            if (sdr.isEmpty() || sdr.size == supported.size) continue
            exo.trackSelectionParameters = params.buildUpon()
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, sdr))
                .build()
            return
        }
    }

    private fun isHdr(f: Format): Boolean {
        val t = f.colorInfo?.colorTransfer
        return t == C.COLOR_TRANSFER_ST2084 || t == C.COLOR_TRANSFER_HLG || f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
    }

    /**
     * 解码方式：只调整解码器的优先顺序（稳定排序，同类保持系统给的顺序），不是强制。
     * 优先硬件：硬件解码器排前面（省电、发热低）；优先软件：软件解码器排前面（个别机型硬解花屏 / 绿屏时用）。
     * 排前面的初始化失败时，是否换下一个由「解码器回退」决定。
     */
    private fun decoderSelector(mode: Int): MediaCodecSelector = when (mode) {
        PlayerPrefs.DECODER_HARDWARE -> MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).sortedBy { !it.hardwareAccelerated }
        }
        PlayerPrefs.DECODER_SOFTWARE -> MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).sortedBy { it.hardwareAccelerated }
        }
        else -> MediaCodecSelector.DEFAULT
    }

    /** 切到第 [i] 个视频源，保留当前进度（直播流从头 / 现场开始）；清晰度选择随新源重置。 */
    fun playSource(i: Int) {
        if (i !in sources.indices || i == sourceIndex) return
        val pos = if (isLive) 0L else exo.currentPosition.coerceAtLeast(0)
        sourceIndex = i
        error = null
        selectedQuality = null
        qualities = emptyList()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        exo.setMediaItem(buildItem(currentRequest), pos)
        exo.prepare()
        exo.play()
    }

    /**
     * 两种显示方式：TextureView 画进 View 层级，能被 miuix 的 layerBackdrop 采样做玻璃模糊；
     * SurfaceView 由系统合成器直接送显，更省电、延迟低，但采样不到（界面会退回不透明底）。
     * 切换时界面会先建新 View 再释放旧的，ExoPlayer 的 clear 只在 View 仍是当前输出时才生效，顺序无所谓。
     */
    /** 当前接着视频输出的 View：截屏要从它取画面 */
    private var videoView: View? = null

    fun attach(view: View) {
        videoView = view
        when (view) {
            is TextureView -> exo.setVideoTextureView(view)
            is SurfaceView -> exo.setVideoSurfaceView(view)
        }
    }

    fun detach(view: View) {
        if (videoView === view) videoView = null
        when (view) {
            is TextureView -> exo.clearVideoTextureView(view)
            is SurfaceView -> exo.clearVideoSurfaceView(view)
        }
    }

    /**
     * 截取当前正在播放的画面（按视频原始分辨率，长边最多 3840）。必须在主线程调用，回调也在主线程；
     * 拿不到（还没出画面 / 受保护内容 / View 已销毁）回 null。
     * TextureView 直接读纹理；SurfaceView 走 PixelCopy（两种渲染方式都能截）。
     */
    fun captureFrame(onDone: (android.graphics.Bitmap?) -> Unit) {
        val v = videoView
        val size = exo.videoSize
        if (v == null || size.width <= 0 || size.height <= 0) { onDone(null); return }
        val k = minOf(1f, 3840f / maxOf(size.width, size.height))
        val w = (size.width * k).toInt().coerceAtLeast(2)
        val h = (size.height * k).toInt().coerceAtLeast(2)
        when (v) {
            is TextureView -> onDone(runCatching { v.getBitmap(w, h) }.getOrNull())
            is SurfaceView -> {
                val bmp = runCatching { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) }.getOrNull()
                if (bmp == null) { onDone(null); return }
                runCatching {
                    android.view.PixelCopy.request(v, bmp, { r -> onDone(if (r == android.view.PixelCopy.SUCCESS) bmp else null) }, android.os.Handler(android.os.Looper.getMainLooper()))
                }.onFailure { onDone(null) }
            }
            else -> onDone(null)
        }
    }

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

    fun changeSpeed(s: Float) {
        exo.playbackParameters = PlaybackParameters(s)
        speed = s
    }

    /** 拖到 0 以上自动取消静音；拖到 0 等同静音（界面按 [muted] 或 volume == 0 显示喇叭关） */
    fun changeVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        if (volume > 0f) muted = false
        applyVolume()
    }

    fun toggleMute() {
        if (muted || volume == 0f) {
            muted = false
            if (volume == 0f) volume = 0.5f // 从 0 取消静音时给个可听见的音量
        } else {
            muted = true
        }
        applyVolume()
    }

    private fun applyVolume() {
        exo.volume = if (muted) 0f else volume
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
