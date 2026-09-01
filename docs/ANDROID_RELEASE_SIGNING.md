# Android 发行签名

GitHub Release 中的 Android APK 必须使用固定的 Worldloom 发行证书。普通分支和 Pull Request 仍构建 Debug APK；`v*` 标签构建只接受签名后的 Release APK，并校验公开证书指纹：

```text
release/android-signing-cert.sha256
```

工作流需要以下 GitHub Actions repository secrets：

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Windows 维护者可以运行一次：

```powershell
./tools/configure-android-release-signing.ps1
```

脚本会创建发行密钥、配置上述 Secrets，并把唯一可恢复的私钥和密码保存在被 Git 忽略的 `.worldloom-signing/`。必须把整个目录转移到受控的离线密码库或加密备份；不得提交、上传到 Release、聊天或构建日志。已有该目录时脚本会拒绝覆盖，避免无意轮换证书。

本地验证签名包时，先把忽略目录中的环境配置导入当前 PowerShell，再构建：

```powershell
Get-Content .worldloom-signing/android-release-credentials.env | ForEach-Object {
    $name, $value = $_ -split '=', 2
    Set-Item -Path "Env:$name" -Value $value
}
$env:WORLDLOOM_REQUIRE_ANDROID_RELEASE_SIGNING = "true"
./gradlew.bat :apps:androidApp:verifyReleaseUiAssets
```

## 0.0.1/0.0.2 迁移说明

`v0.0.1` 与 `v0.0.2` 错误地发布了由不同临时 Debug 证书签名的 APK。相应 SHA-256 证书指纹分别是：

```text
v0.0.1  a2bc7696ff8eef8148c548de33ea31022403460fb2562c332b8f3f21d05e9992
v0.0.2  d83ef2b00d591a86800e2f1eddbd3b9865fe67274a9192563299b449fe11b0a4
```

临时 Runner 已销毁，旧私钥无法恢复。因此从这些版本迁移到首个固定签名版本 `0.0.3` 时仍需卸载旧应用一次；从 `0.0.3` 开始，只要发行私钥没有丢失或被替换，后续更高 `versionCode` 的 APK 可以直接覆盖升级。
