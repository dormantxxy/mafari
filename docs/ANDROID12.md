# Android 12 适配分支（非官方）

基于 [rosenii/mafari](https://github.com/rosenii/mafari) 的 `90a06764b0ef5d5eb882ad63e9835972a31ce2d6`，分支为 `android12`。
原作者及第三方组件的版权声明保持不变，本分支不代表原作者的官方发布。

## 兼容范围

- 最低 Android 12（API 31），同时覆盖 Android 12L（API 32）及更新系统。
- 保留 lite（三种 ABI）、sync_lite（arm64）和 gecko（arm64）变体。
- Android 12 没有 AGSL RuntimeShader，沿用 miuix / Backdrop 的能力判断；玻璃折射及渐进模糊退回底色，不强行调用 API 33。
- miuix-blur 的安装门槛通过第 12 个本地补丁降至 API 31，其 shader 管线仍由原有能力判断保护。
- 自动填充 Parcelable 使用 AndroidX IntentCompat；查询应用名使用兼容的 flags=0 重载。
- 通知运行时权限只在 API 33+ 检查/请求；画中画动态广播使用 ContextCompat 的非导出兼容实现。
- 密码 CSV、Git 私钥导入和 HLS 嗅探用有大小上限的兼容流读取，保留原有超限拒绝和错误处理。
- 应用更新列表、安装包 digest 和问题反馈指向本 fork，避免下载原作者不同签名的 APK。

## 固定发布签名

发布密钥只生成一次，并保存在 GitHub Actions Secrets；不得在每次构建中生成新密钥。
仓库只保存公开的证书 SHA-256：[android12-signing.sha256](android12-signing.sha256)。

必需的 Secrets：

- `KEYSTORE_BASE64`：固定 JKS 的 Base64
- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

CI 缺少任何参数直接失败，不退回随机 debug 签名。每个 APK 在发布前用 apksigner 验证 API 31/32 下的签名，并与仓库固定指纹比较。
本分支和原作者 APK 的签名不同，不能互相覆盖安装。已有数据请先使用应用内加密备份；不要为了安装自动卸载或清除数据。

## 构建与检查

```bash
bash tools/miuix-sync.sh
# release 构建需要设置 MAFARI_KEYSTORE / MAFARI_STORE_PASS / MAFARI_KEY_ALIAS / MAFARI_KEY_PASS
bash ./gradlew :app:assembleLiteArm64Release
# 针对 API 的 lint 和全套现有 JVM 单元测试
bash ./gradlew :app:lintLiteArm64Release :app:testLiteArm64ReleaseUnitTest -Pandroid12ApiCheck=true
```

`android12` 分支推送触发 CI 构建与 GitHub Release。发布包含各变体的 APK、SHA256SUMS.txt 和公开签名指纹。
CI 的 Android 12（API 31）x86_64 模拟器用于 lite release 的首次启动/网页打开冒烟验证；这不等同所有厂商真机、Gecko、Firefox Sync、相机、投屏或长期后台播放都经过完整回归。
