# QuarkDav Cookie 内核移植说明

本文记录将附件 `QuarkDav-Android` 中基于 Cookie 的夸克/UC 网盘 WebDAV 能力移植进 Round-Sync 的设计、代码边界、配置映射、后台生命周期、日志链路和验证方法。

## 1. 两个项目的原始架构差异

### Round-Sync

Round-Sync 的核心不是通过 Java/Kotlin 库直接调用 rclone，而是把 rclone 编译为 Android 原生可执行文件，并以 `librclone.so` 的名称随 APK 打包。Android 代码通过 `ProcessBuilder` 启动该内核，配置保存在 `rclone.conf`，文件管理、同步任务和各种 `rclone serve` 功能均围绕这一进程模型组织。

### QuarkDav-Android

附件中的 QuarkDav-Android 原本通过 gomobile 将 Go 内核打包为 AAR，由单个 Android 前台服务调用。Go 侧同时包含 `cookie`、`open` 和 `tv` 三类驱动，并实现完整 WebDAV 服务器。

### 本次选择

本次没有把 QuarkDav 嵌入 rclone 进程，也没有继续使用 gomobile 单例，而是新增与 `librclone.so` 同层的 `libquarkdav.so` 独立内核：

```text
Round-Sync UI / 配置仓库
        │
        ├── rclone Android 调度 ──> librclone.so
        │                              │
        │                              └── 可用 WebDAV other 连接本机 QuarkDav
        │
        └── QuarkDavService ─────> libquarkdav.so（每个 Quark 远端一个子进程）
```

这样做的直接收益是：

1. QuarkDav 与 rclone 是真正的同级内核，互不共享进程生命周期。
2. rclone 任务取消、远端浏览、配置刷新或 `serve` 进程退出不会误杀 QuarkDav。
3. 多个 Cookie 远端可以使用不同端口并行运行。
4. QuarkDav 服务进程意外被回收后，可以识别并清理遗留子进程，再按持久化配置恢复。
5. rclone 可以通过标准 `webdav` + `vendor=other` 访问 QuarkDav，不需要修改 rclone 本身。

## 2. 移植边界

只保留 Cookie 驱动，未移植 Open API 和 TV 驱动。

保留的 Go 包：

- `internal/dav`：OPTIONS、GET、HEAD、POST、PROPFIND、PROPPATCH、PUT、MKCOL、DELETE、COPY、MOVE、LOCK、UNLOCK。
- `internal/drive`：统一驱动接口。
- `internal/quarkcookie`：夸克/UC Cookie 登录、目录和文件操作、下载地址、上传与 Cookie 刷新。
- `internal/remotefs`：WebDAV 文件系统适配、缓存、移动/复制/上传等。
- `internal/util`：路径和日志辅助逻辑。

未包含：

- `internal/quarkopen`
- `internal/quarktv`
- Open/TV 配置结构和 UI
- QuarkDav 原有独立日志目录配置

Go 运行时会强制 `driver = cookie`，即使运行配置被手工写成其他值，也会在规范化时恢复为 Cookie 驱动。

## 3. Cookie 驱动功能保留情况

移植后的内核继续具备：

- 夸克与 UC 品牌选择。
- Cookie 鉴权和 `__puus` / `__pus` 自动刷新。
- 指定根目录 ID。
- 文件名、更新时间、创建时间、文件大小等排序字段以及升降序。
- 可选转码播放地址。
- 可选仅列出视频文件。
- 目录缓存 TTL。
- Range 下载、HEAD 和常见条件请求透传。
- 普通与分片上传、流式上传、新建目录、删除、移动、重命名、复制。
- WebDAV 锁和死属性处理。
- Basic Auth 或无认证模式。
- 自定义监听地址、端口、URL 前缀和临时目录。

Cookie 自动刷新采用双文件模型：Android 元数据文件保存 UI 和生命周期属性；Go 运行配置只保存内核字段。Go 可以安全更新运行配置中的 Cookie，Android 服务再在心跳、正常退出或恢复时同步回元数据，不会覆盖名称、开机启动等 Android 属性。

## 4. 配置属性映射

| QuarkDav 配置 | Round-Sync UI/模型 | 处理方式 |
| --- | --- | --- |
| `listen` | 监听地址 + WebDAV 端口 | 拆成列表项，启动时组合；支持 IPv4、IPv6 与通配地址 |
| `prefix` | WebDAV 路径前缀 | 自动规范为以 `/` 开头的非根路径 |
| `username` | WebDAV 用户名 | 保留 |
| `password` | WebDAV 密码 | 保留，密码输入类型 |
| `no_auth` | 关闭 WebDAV 认证 | 保留 |
| `cache_ttl_seconds` | 目录缓存秒数 | 保留，最小值 1 |
| `temp_dir` | 上传临时目录 | 保留；空值使用应用缓存目录中的远端专属目录 |
| `driver` | 无单独 UI | 固定为 `cookie` |
| `cookie.cookie` | 夸克/UC Cookie | 保留，多行输入 |
| `cookie.root_id` | 根目录 ID | 保留 |
| `cookie.brand` | `quark` / `uc` 下拉列表 | 保留 |
| `cookie.order_by` | 排序字段下拉列表 | 保留 |
| `cookie.order_direction` | `asc` / `desc` 下拉列表 | 保留 |
| `cookie.use_transcoding_address` | 使用转码地址 | 保留 |
| `cookie.only_list_video_file` | 仅列出视频文件 | 保留 |
| `log_dir` | 不提供 | 按要求改为写入 Round-Sync 统一日志 |

新增的 Android 生命周期属性：

- 是否启用 WebDAV 服务。
- 是否在开机/应用更新后恢复。
- 是否在运行期间持有 CPU 与高性能 Wi-Fi 锁。
- 电池优化设置入口。

配置页面复用 Round-Sync 的 `remote_config_form`、CardView、Material 输入框、搜索栏和保存浮动按钮，每个属性独立成可搜索列表卡片，与动态 rclone 远端配置的交互位置保持一致。

## 5. 多实例后台服务

`QuarkDavService` 运行在独立的 `:quarkdav` 前台进程中，并返回 `START_STICKY`。服务读取所有已启用远端，为每个远端启动独立 `libquarkdav.so --config <file>` 子进程。

状态机：

```text
STOPPED -> STARTING -> RUNNING
                    \-> ERROR -> 指数退避重启
RUNNING -> 配置变化/手动关闭 -> STOPPED
```

主要稳定性措施：

- 每个远端独立进程，单实例故障不会影响其他远端或 rclone。
- 配置指纹变化只重启对应实例。
- 异常退出按 2、4、8、16、32、60 秒上限退避重启。
- 45 秒心跳更新状态并同步自动刷新的 Cookie。
- 服务进程重建时读取上次 PID，同时扫描 `/proc/*/cmdline`，识别匹配运行配置的遗留内核。
- 清理遗留实例后再启动新进程，避免同端口双重监听。
- 旧进程缓冲区中的 READY 消息必须通过当前实例身份校验，不能覆盖新实例状态。
- 手动关闭会持久化 `enabled=false`；仅关闭配置页面、离开远端列表或执行 rclone 操作不会停服。
- 通知栏提供“全部停止”，只在用户明确操作时关闭所有 QuarkDav 实例。

## 6. rclone 互操作

QuarkDav 远端菜单新增“创建/更新 rclone WebDAV 远端”。执行顺序为：

1. 将目标 QuarkDav 远端标记为启用并协调后台服务。
2. 等待本机监听地址的 TCP 端口实际可连接；同时监测后台错误状态，最长等待 65 秒。
3. 创建或覆盖名为 `quarkdav_<安全名称>` 的 rclone 配置。
4. 配置类型为 `webdav`，`vendor=other`，URL 指向本机 QuarkDav 地址。
5. 根据 `no_auth` 决定是否写入用户和经过 rclone `--obscure` 处理的密码。

典型链路：

```text
其他 App
  -> Round-Sync 的 rclone serve WebDAV/HTTP/FTP/DLNA
  -> rclone WebDAV other 远端
  -> 127.0.0.1:<QuarkDav端口>/<前缀>/
  -> QuarkDav Cookie 驱动
  -> 夸克/UC 网盘
```

QuarkDav 与 rclone 服务运行在不同 Android 进程，且各自持有独立原生子进程，因此停止一次 rclone 任务、重新浏览远端或修改其他 rclone 配置不会导致 QuarkDav 掉线。

## 7. Round-Sync 日志改造

原 `SyncLog` 改为按日期分割的 JSON Lines 文件：

```text
files/logs/sync-YYYY-MM-DD.jsonl
```

改造内容：

- rclone 业务日志和 QuarkDav 标准输出/错误输出写入同一套日志模型。
- 每条记录包含 UUID、时间戳、标题、内容和类型。
- 进程内互斥锁避免 JVM 的重叠文件锁异常；文件锁负责 UI、QuarkDav 和串流进程之间的同步。
- 写入后 flush + fsync，降低进程意外退出时的丢失概率。
- 默认保留 31 天；读取最新 5000 条。
- 对高日志量日期读取文件尾部，确保最新日志不会被当天旧记录挤掉。
- 日志页接收应用内广播，150 ms 合并刷新，后台线程读取，避免阻塞主线程。
- 支持整批清空，也支持左右滑动按 UUID 删除单条记录。
- 兼容读取和删除旧版 `files/sync.log`。

## 8. 原 rclone 串流后台逻辑改造

`StreamingService` 调整为独立 `:streaming` 前台进程，并启用 Intent redelivery。服务实际提供 rclone 串流时持有：

- `PARTIAL_WAKE_LOCK`
- `WIFI_MODE_FULL_HIGH_PERF` Wi-Fi 锁

服务退出、异常和线程中断统一在 `finally` 中销毁原生进程、释放锁并移除前台通知，避免资源泄漏。与 QuarkDav 的独立前台进程配合后，多层链路的上下游不会因主界面销毁而同时退出。

## 9. 关键代码位置

| 位置 | 作用 |
| --- | --- |
| `quarkdav/quarkdav-src/cmd/quarkdav/main.go` | Cookie-only WebDAV 原生内核入口 |
| `quarkdav/quarkdav-src/internal/*` | 保留的 QuarkDav Cookie/WebDAV 实现 |
| `quarkdav/build.gradle` | 四 ABI 原生内核构建 |
| `quarkdav/NOTICE.md` | 来源与许可证说明 |
| `app/.../quarkdav/QuarkDavRemote.kt` | 配置和远端列表模型 |
| `app/.../quarkdav/QuarkDavRepository.kt` | 跨进程原子配置仓库 |
| `app/.../quarkdav/QuarkDavService.kt` | 多实例前台服务与子进程监督 |
| `app/.../quarkdav/QuarkDavStatusStore.kt` | 运行状态持久化/广播 |
| `app/.../quarkdav/QuarkDavRcloneIntegration.kt` | rclone WebDAV other 一键配置 |
| `app/.../RemoteConfig/QuarkDavConfigFragment.kt` | Round-Sync 风格配置 UI |
| `app/.../Fragments/RemotesFragment.java` | 远端列表、启停、复制地址、编辑、删除入口 |
| `app/.../util/SyncLog.java` | 按日、多进程安全日志 |
| `app/.../Fragments/LogFragment.java` | 实时读取、清空和滑动删除 |
| `app/.../Services/StreamingService.java` | rclone 串流后台保活改造 |

## 10. 构建与验证

Go 内核单元测试：

```sh
cd quarkdav/quarkdav-src
go test ./...
```

Android 构建仍使用项目原有入口，`preBuild` 会同时构建 rclone 和 QuarkDav 四 ABI 内核：

```sh
./gradlew assembleOssDebug
```

要求：JDK 17、Go 1.22+、Android SDK，以及 `gradle.properties` 指定版本的 NDK。输出 APK 中每个 ABI 目录应同时包含：

```text
librclone.so
libquarkdav.so
```

建议在真机完成以下回归：

1. 新建两个不同端口的 Cookie 远端并同时启用。
2. 切后台、熄屏后从另一 App 连续读取大文件。
3. 用“一键创建 rclone WebDAV 远端”生成 `vendor=other` 配置并浏览、Range 播放、上传、移动、删除。
4. 再通过 `rclone serve` 暴露该 rclone 远端，验证多层串流。
5. 修改运行中远端的 Cookie、端口和前缀，确认只重启目标实例。
6. 杀掉主界面进程，确认前台服务仍可访问；再验证系统回收后的恢复。
7. 重启设备，检查启用且允许开机恢复的实例。
8. 检查日志按日期写入、实时刷新、单条删除和全部清空。

## 11. 安全和平台边界

- Cookie 等同于网盘登录凭据，配置文件位于应用私有目录，但设备备份、root 环境和调试包仍需按敏感数据处理。
- 使用 `0.0.0.0` 或 `::` 会向可达网络接口开放服务；应设置强用户名和密码，除非明确只在受控环境中使用。
- CPU/Wi-Fi 锁和前台服务显著提高后台连续传输稳定性，但 Android 用户“强行停止”应用、设备关机、系统极端内存回收或厂商额外后台策略仍可能终止服务。应用无法绕过这些系统级决定。
- 该实现提供本机/局域网 WebDAV 服务，不是在非 root Android 上创建系统级 FUSE 盘符。

## 12. 许可证

附件 QuarkDav-Android 的许可证为 AGPL-3.0。保留和修改的 Cookie 内核代码位于 `quarkdav/`，该目录包含 `LICENSE-AGPL-3.0` 和 `NOTICE.md`。Round-Sync 其余代码继续遵循项目原有许可证和声明。
