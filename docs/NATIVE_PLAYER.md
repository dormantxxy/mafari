# 原生播放器（Media3 ExoPlayer）

目标：脱离 `float-player.js`，播放内核、界面、画中画都由原生承担；页面里只留一小段 App 自带的嗅探脚本。分阶段推进。

| 阶段 | 内容 | 状态 |
|---|---|---|
| 一 | 播放内核 + 最简播放页，宿主 `play` 操作可拉起 | 已完成（未在真机验证） |
| 二 | miuix 悬浮窗界面（页内小窗拖动、手势、视频源列表、全屏） | 待做 |
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
