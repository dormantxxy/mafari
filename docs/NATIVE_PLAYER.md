# 原生播放器（Media3 ExoPlayer）

目标：脱离 `float-player.js`，播放内核、界面、画中画都由原生承担；页面里只留一小段 App 自带的嗅探脚本。分阶段推进。

| 阶段 | 内容 | 状态 |
|---|---|---|
| 一 | 播放内核 + 最简播放页，宿主 `play` 操作可拉起 | 已完成（未在真机验证） |
| 二 | 与脚本一致的 miuix 播放界面（只做界面：卡片 / 全屏、控件、手势、倍速 / 清晰度弹层） | 已完成（未编译、未在真机验证） |
| 三 | 原生画中画 / 后台播放 | 待做 |
| 四 | 嗅探（WebView `shouldInterceptRequest`、Gecko webRequest 上报、精简注入脚本） | 待做 |
| 五 | 开关迁到 AppSettings，下载 / 投屏 / 外部播放器直连，删除脚本 | 待做 |

## 阶段一的结构（`player/`）

- `MafariPlayer`：ExoPlayer 封装，状态用 Compose State 暴露（播放 / 缓冲 / 进度 / 倍速 / 清晰度 / 错误）。界面无关，后续悬浮窗、画中画共用同一个实例。
- `PlayerRequest`：地址 + Referer / UA / Cookie。原生请求直接带头，没有跨域问题。
- `StripImageHeaderDataSource`：包在 HTTP 数据源外，裁掉伪装成图片的 TS 分片前缀。判定规则与 `download/HlsDownload.stripFakeImageHeader` 一致（`FakeImageHeader`，纯函数，有单测）。
- `NativePlayerActivity`：最简 Compose 播放页（点按显隐控件、双击左右 10 秒、进度条、倍速、清晰度、重试）。

## 触发（临时，阶段四前用）

脚本侧（需要 `@grant GM_mafariHost`）：

```js
GM_mafariHost('play', { url, title, referer: location.href, ua: navigator.userAgent, cookie: document.cookie, type: 'hls', pos: 0 })
```

`caps` 返回里多了 `play: true`。

## 已知取舍 / 待验证

- 网络层用 `DefaultHttpDataSource`（HttpURLConnection），没引 `media3-datasource-okhttp`：项目的 OkHttp 是 5.x，该模块按 4.x 编译，不确定二进制兼容。需要 HTTP/2 / 连接复用时再评估。
- 请求头对所有请求一视同仁（含跨域的分片 / 密钥）。和脚本现状一致，但 Cookie 会被带到分片域名；阶段二可按 host 限定。
- 清晰度只列出 ExoPlayer 判定为可解码的轨道；同高度只留最高码率。
- 阶段一离开界面（`onStop`）即暂停。

## 阶段二的结构（`player/`）

- `PlayerSurface`：播放界面本体，一比一对照 `float-player.js` 的 DOM —— scrim 暗角、中部 后退10 / 播放 / 快进10、底部 时间 + 胶囊进度条、左下静音、右下 倍速 / 画中画 / 全屏、左上 ✕（全屏时 ‹）、右上 ⋯ 菜单。手势：单击显隐，双击左 / 右 40% 快退 / 快进，双击中间播放 / 暂停，长按 2x（松手恢复）。3.8 秒无操作自动收起。
- `PlayerIcons`：脚本里的 SVG 路径原样搬过来，用 `PathParser` + Canvas 绘制，不依赖 Material Icons。
- `NativePlayerActivity`：竖屏是顶部 16:9 圆角卡片（✕ 关闭）+ 下方标题；全屏钮铺满并转横屏，‹ / 返回键退出。只有一处 `PlayerSurface` 调用，切换时界面状态不丢。
- `MafariPlayer`：新增 `muted` / `toggleMute()`。

### 阶段二没做的（按约定只做界面）

- 画中画、下载视频、复制视频链接：`PlayerSurface` 留了 `onPip` / `onDownload` / `onCopyLink` 三个可空回调，传 null 时按钮照常显示但灰掉（和脚本里 disabled 的样式一致）。
- 视频源列表、页内小窗拖动、投屏、外部播放器。

### 材质（miuix-glass）

- 按钮用 `GlassIconButton`，倍速 / 更多菜单用 `Modifier.glassPanel`，采样源是视频层的 `LayerBackdrop`（只录视频层，玻璃表面都在录制层外）。
- 图标优先用 `MiuixIcons.Glass.*`；Glass 图集里没有的 4 个（后退 10 秒、快进 10 秒、画中画、退出全屏）仍用 `PlayerIcons` 自绘。
- 视频改用 `TextureView`（`MafariPlayer.attach/detach`）。原因：Compose 的背景采样录的是 View 层级里画出来的内容。WebView 开硬件层之后能被采样，TextureView 同理；`SurfaceView` 的画面由系统合成器单独送显，不在这条绘制链里，采不到（Gecko 内核不采样也是这个原因）。
- 代价：TextureView 比 SurfaceView 略耗电，没有 HDR / DRM 直通；视频播放时每帧都会重录 + 重算模糊，低端机可能掉帧。需要真机确认，必要时给玻璃加开关或降低模糊。
- 进度条仍是自绘的胶囊条（和脚本一致）；miuix 的 `Slider` 外观不同，要统一的话可以换。

### 显示方式切换 / 音量

- ⋯ 菜单最下面的「显示方式」：Texture / Surface 二选一，默认 Texture。切换时视频 View 整个重建（`key(textureMode)`），播放不中断。`MafariPlayer.attach/detach` 同时支持 `TextureView` 和 `SurfaceView`。
- Surface 模式录不到画面：按钮 / 弹层的采样源传 null，miuix 玻璃自动退回不透明底（弹层用自带的 fallback 底）。
- 左下音量钮改为点开弹层：喇叭点一下静音 / 取消静音，滑条拖动 0–100%，右边显示百分比。调的是 ExoPlayer 自身音量（`MafariPlayer.volume`），叠在系统媒体音量之上，不改系统音量。
- 显示方式不持久化，每次打开播放页回到 Texture。

### 其他和脚本不一样的地方

- 圆角：Compose 的 `clip` 对 `TextureView` 是有效的（SurfaceView 不行），卡片圆角应该能正常显示，需真机确认。
- 方向：脚本没有方向逻辑；原生页改成按钮驱动（卡片锁竖屏，全屏转横屏），不再跟随传感器。
