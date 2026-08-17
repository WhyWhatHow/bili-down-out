# AGENTS.md

本文件面向 AI 编码代理（以及新加入的人类开发者），说明项目结构、核心机制、构建与发布流程。修改本项目前请先读完本文档。

## 项目简介

**bili-down-out** 是一个 Android 应用，用于将哔哩哔哩客户端下载到本地的离线缓存（`Android/data/tv.danmaku.bili/download/` 下的 entry.json + 视频/音频分片）合并导出为单个 MP4 文件，并按 UP 主分类归档到 `Download/BiliDownOut/<UP主>/`。

- 上游原项目：https://github.com/10miaomiao/bili-down-out
- 本 fork（发版仓库）：https://github.com/WhyWhatHow/bili-down-out
- 技术栈：Kotlin + Jetpack Compose (Material3) + Room + DataStore + Shizuku + OkHttp + kotlinx.serialization
- minSdk 21 / targetSdk 35 / compileSdk 35

## 文档索引（项目结构相关）

| 文档 | 内容 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 面向 AI 代理/新人的项目结构、核心机制、构建与发布流程（即本文件，含下方"目录结构与关键文件"） |
| [README.md](README.md) | 项目介绍、声明与 APK 下载入口 |
| [fastlane/metadata/android/](fastlane/metadata/android/) | 应用商店元数据（多语言描述、截图、changelog） |
| [.github/workflows/android.yml](.github/workflows/android.yml) | CI/CD：推送 `v*` 标签自动打 debug 包并发布到 GitHub Release |

## 构建与测试

```bash
# 必须 JDK 17（系统默认 Java 25 与 Gradle 8.2.1 不兼容）
export JAVA_HOME=~/.local/share/mise/installs/java/17.0.2

./gradlew testDebugUnitTest   # 单元测试
./gradlew assembleDebug       # 打 debug APK
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

注意：
- 无 release 签名（缺 `app/signing.properties` + keystore），一律出 debug 包。
- **每次打包必须递增版本号**：`app/build.gradle` 中 `versionCode` +1、`versionName` 递增（当前 106 / "1.4.2"）。
- 交付 APK 时复制到仓库根目录并按 `BiliDownOut-<版本>-debug.apk` 命名。
- Android SDK 位于 `/opt/android-sdk`（local.properties 已配置则无需关心）。

## 发布流程（每次成果交付）

1. 确认单测通过、APK 打包成功、版本号已递增。
2. git 提交所有变更（提交信息用中文，简述改动）。
3. 推送到 `origin` 远端（WhyWhatHow/bili-down-out）main 分支。
4. 打 tag 并推送：`git tag -a v<版本> -m "说明" && git push origin v<版本>`。
5. CI/CD（`.github/workflows/android.yml`）自动完成：跑单测 → 打 debug 包 → 以 `BiliDownOut-<版本>-debug.apk` 发布到 GitHub Release（tag 信息作为 Release 说明）。
6. 若 CI 不可用，可用 GitHub CLI 手动发版：`gh release create v<版本> BiliDownOut-<版本>-debug.apk --title ... --notes ...`。

## 目录结构与关键文件

```
app/src/main/java/cn/a10miaomiao/bilidown/
├── BiliDownApp.kt                  # Application 入口：数据库、AppState、UP主缓存初始化
├── common/
│   ├── BiliAuthorRepository.kt     # UP主名称+头像补全：B站 view API，内存+磁盘缓存+重试
│   ├── BiliEntryJsonParser.kt      # entry.json 安全解析（容错空/损坏 JSON）
│   ├── BiliDownFile.kt             # 读取哔哩哔哩下载目录（SAF / Shizuku 两种通道）
│   ├── BiliDownOutFile.kt          # 导出目标文件路径管理与文件名清洗
│   ├── MiaoLog.kt                  # 极简日志
│   └── datastore/                  # DataStore 偏好（导出后是否删除源文件等）
├── entity/
│   ├── BiliDownloadEntryInfo.kt    # entry.json 数据模型（owner/ep/source/page_data）
│   ├── DownloadInfo.kt             # 列表实体 + groupByAuthor/applySort/formatFileSize
│   │                               #   ★ UP 分组、排序（默认/文件名/大小）、番剧兜底标签
│   └── OutRecord 等                # Room 记录
├── db/                             # Room 数据库（导出任务队列记录）
├── service/BiliDownService.kt      # 前台服务：合并导出任务队列，kickQueue 自动启动下一条
├── shizuku/                        # Shizuku 免 root 访问 /Android/data
├── state/                          # AppState（taskStatus StateFlow 等）
└── ui/
    ├── MainComposeApp.kt           # Scaffold + 底部导航（固定不隐藏）+ NavHost
    ├── page/
    │   ├── DownloadListPage.kt     # ★ 主列表页：UP分组折叠/排序栏/多选批量导出/异步UP补全
    │   ├── AuthorPage.kt           # ★ UP主视频页：点分组头UP名称进入，支持多选批量导出
    │   ├── DownloadDetailPage.kt   # 单视频详情（分P列表、导出）
    │   ├── ProgressPage.kt         # 导出任务队列页
    │   ├── OutListPage.kt          # 已导出文件列表
    │   └── MorePage.kt             # 设置页（删除源文件开关等）
    └── components/                 # DownloadListItem、BatchExportDialog 等
```

## 核心机制（改代码前必读）

### 1. 列表加载与 UP 主补全（DownloadListPage）

- 采用 molecule 模式：`DownloadListPagePresenter(context, actionFlow)` 返回 State，UI 通过 channel 发 Action。
- 流程：`getList` 先同步读 entry.json 渲染列表（**不能被网络阻塞**），随后 `fillMissingAuthors` 异步补全缺失 UP 主，完成后整体刷新。
- 新版 B 站客户端 entry.json 无 `owner`，需要调 B 站 API 补全：
  - key 兜底顺序 `bvid -> ep.bvid -> source.av_id -> avid`（番剧条目常缺顶层 bvid/avid，老视频无 bvid 走 avid）。
  - 并发 4 路 + 单条目一次重试，避免风控(-412)。
  - 结果持久化在 `filesDir/bili_author_cache.json`（`BiliAuthorRepository.init()` 在 App 启动时加载），**原子写**（.tmp + rename，防止写一半被杀丢整份缓存），兼容旧版纯 Map 格式。
  - **负缓存（勿移除）**：会员/付费视频 view API 永远返回错误码，失败 key 记录时间戳，24h 内直接跳过不再请求；`getAuthor` 只写内存，批量完成后由调用方 `persist()` 统一落盘。
  - `peekAuthor()` 只读查询不发网络，供缓存优先路径（UP 页兜底）毫秒级补全。
- **番剧/影视没有 UP 主**：author 为空且 type==BANGUMI 的统一归入 `BANGUMI_GROUP_LABEL`（"番剧·影视"）分组，不再显示"未知UP主"。
- UP 分组默认收起（`expandedGroups` 状态），点击分组头右侧箭头展开/收起；点击 UP 主信息区（头像+名称）跳转 `AuthorPage`（该 UP 的视频列表，含多选批量导出）。
- entry -> DownloadInfo 的映射逻辑在 `entity/DownloadInfo.kt` 的 `buildDownloadInfoList()`，列表页与 UP 主页共用，勿在页面里复制粘贴。
- **列表缓存（勿破坏）**：`AppState.downloadListCache[packageName]` 由列表页加载/刷新时写入（UP 补全完成后也会刷新），UP 页兜底扫描完成后也回写。`AuthorPage` 必须缓存优先、磁盘仅兜底——全量遍历 `/Android/data` 在 SAF 下每个文件操作都是一次 IPC，重复执行会导致页面明显卡顿。兜底路径调 `fillMissingAuthors(fetchRemote = false)` 只查本地缓存，不等网络。
- **AuthorPage 的 loaded 标记（勿移除）**：空结果也算加载完成，防止"空列表 + Shizuku 状态变化"触发 LaunchedEffect 无限重扫。
- **Shizuku binder 调用必须包 `withTimeout`**（`BiliDownFile.SHIZUKU_CALL_TIMEOUT_MS`，60s）：binder 不响应协程取消，服务进程卡死会无限阻塞 UI。

### 2. 导出队列（BiliDownService）

- `tryAddTask` 入队后调用 `kickQueue()`：空闲时立即启动最早等待任务（**不要依赖 taskStatus StateFlow 变化触发**，状态未变时不会重发射）。
- "导出后删除源文件"是全局设置（MorePage 开关，DataStore `exportDeleteSource`，默认关）。批量导出 BatchExportDialog 展示删除警示；单个导出 FileNameInputDialog 不再弹删除相关提示。
- 批量导出的文件检查与入库必须包在 `withContext(Dispatchers.IO)` 中。

### 3. UI 约定

- 底部导航栏固定显示（曾按滚动隐藏，已按需求移除，勿恢复 `ScaffoldScrollableState` 逻辑）。
- 分组头：头像（网络头像或首字符字母头像 AuthorAvatar）+ 名称 + "N个视频 · 总大小"，右侧展开箭头；多选模式右侧变"全选"。
- 长按列表项进入多选；底部浮条"全选/导出选中/退出"。
- 排序栏：默认/文件名/大小 FilterChip；点击已选中的"文件名/大小"chip 在 升序(↑)->降序(↓) 间循环逆转，方向显示在 chip 文案内（中文用 `Collator(CHINA)` 拼音排序）。
- 列表项信息行：多P显示"NP · "，状态"已完成/暂停中"，再加总大小；单P不显示数量。

## 单元测试

位于 `app/src/test/java/...`，重点覆盖：
- `BiliEntryJsonParserTest`：entry.json 容错解析。
- `BiliAuthorRepositoryTest`：view API 响应解析（name/face/异常）。
- `DownloadInfoTest`：分组、番剧兜底、排序、大小格式化。

改实体/排序/解析逻辑必须同步补测试。

## 已知坑

1. **JDK 版本**：默认 Java 25 会让 Gradle 8.2.1 报 "Unsupported class file major version 69"，必须 JDK 17。
2. **entry.json 字段可缺失**：`avid/bvid/owner/season_id` 都可能为 null，解析用 `BiliEntryJsonParser` 且模型字段全部给默认值。
3. **B站 API 风控**：view API 高频无 Cookie 请求会 -412，保持低并发（≤4）+ 重试 + 磁盘缓存，勿移除。
4. **/Android/data 访问**：Android 10+ 走 SAF（MiaoDocumentFile）或 Shizuku（RemoteServiceUtil），两条通道都要兼容。
5. **debug 签名**：升级安装无需卸载（同签名）；换签名需卸载重装，用户本地缓存会丢。
