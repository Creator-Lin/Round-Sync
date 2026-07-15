# QuarkDav Cookie 内核移植验证记录

验证日期：2026-07-11

## 已完成并通过

1. **Go 格式化、静态检查、测试与主机编译**

   ```sh
   gofmt -w ...
   cd quarkdav/quarkdav-src
   go vet ./...
   go test ./...
   go build ./cmd/quarkdav
   ```

   结果：全部通过。测试覆盖 Cookie-only 驱动强制规范化、路径前缀/TTL/品牌/排序方向规范化、自动刷新 Cookie 的运行配置持久化，以及 IPv4/IPv6 WebDAV URL 输出。

2. **保留源码一致性**

   与附件 QuarkDav-Android 逐文件比较：

   - `internal/dav`：一致
   - `internal/drive`：一致
   - `internal/quarkcookie`：一致
   - `internal/remotefs`：一致
   - `internal/util/logdisplay.go`：一致
   - `internal/util/util.go`：一致

   `internal/util/logfile.go` 按需求未移植，因为日志已接入 Round-Sync 的统一日志系统。

3. **Open/TV 排除检查**

   新 `quarkdav/quarkdav-src` 中未发现 `quarkopen`、`quarktv`、`OpenConfig`、`TVConfig` 或 Open/TV 驱动选择代码。

4. **Android XML/资源静态检查**

   - 解析 Manifest 与资源 XML：265 个文件全部成功。
   - 检查新增 QuarkDav 字符串引用：56 个引用全部存在于默认资源。
   - 检查 values 资源重复名称：通过。
   - `git diff --check`：通过。

5. **构建接线检查**

   - `settings.gradle` 已包含 `:quarkdav`。
   - App `preBuild` 同时依赖 `:rclone:buildAll` 和 `:quarkdav:buildAll`。
   - `quarkdav/build.gradle` 为四个 ABI 输出 `app/lib/<abi>/libquarkdav.so`。
   - APK 使用原项目的 legacy native library packaging，运行时可从 `nativeLibraryDir` 启动内核。

## 当前环境无法完成的验证

本执行环境没有 Android SDK/NDK，也没有缓存 Gradle 8.2 发行包。执行：

```sh
bash gradlew help --offline
```

Gradle Wrapper 仍需要取得 `gradle-8.2-bin.zip`，但环境无外网，最终以 `UnknownHostException: services.gradle.org` 结束。因此这里没有声称 Android Kotlin/Java 编译、APK 打包或真机测试已通过，也没有提供未经构建验证的 APK。

## 建议在 Android 构建机执行

```sh
./gradlew assembleOssDebug
./gradlew lintOssDebug
```

随后按 `QUARKDAV_COOKIE_KERNEL_PORT.md` 第 10 节进行真机多实例、熄屏串流、rclone WebDAV other、多层 rclone + QuarkDav、开机恢复和日志回归测试。
