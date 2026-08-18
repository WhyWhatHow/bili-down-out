# BiliDownOut：把「一维平铺的缓存列表」重构成「分组 · 排序 · 批量」结构（开发向 · 母稿）

> 定位：面向「技术 / 开发者」的核心稿，由用户向母稿的“同一产品事实”派生，重点讲**架构、实现与约定**。是掘金 / V2EX 平台改稿的母版。下载统一指向 GitHub Releases v1.6.1。

---

## 1 / 在解决什么问题

哔哩哔哩 APP 的离线缓存，被存放在 `Android/data/tv.danmaku.bili/download/` 深处，是一堆 `entry.json` + `blv/*.blv` 视频/音频分片。上游项目 [10miaomiao/bili-down-out](https://github.com/10miaomiao/bili-down-out) 解决了「读取并合并导出」，但我个人使用时遇到三个不便：**查找慢**（反复扫描）、**不能多选**（只能一个个导）、**不能按 UP 主挑选**（列表一维平铺，番剧和 UP 主内容混在一起）。

本项目正是围绕这三点做的一轮增强，已达成稳定版 **v1.6.1**。

## 2 / 一句话设计思路

**把列表从「一维平铺」升级为「先分类、可排序、可批量操作」的结构化视图，并对耗时操作做持久化兜底。**

```
读取缓存 → 解析 entry.json → 清洗归类 → 分组（UP 主 / 番剧）→ 按需排序 → 持久化 → 展示 → 导出
```

## 3 / 核心实现

技术栈：Kotlin · Jetpack Compose (Material3) · Room · DataStore · Shizuku · OkHttp · kotlinx.serialization。`minSdk 21 / targetSdk 35`。

### 3.1 分组与排序（核心）
`entity/DownloadInfo.kt`：
- `List<DownloadInfo>.groupByAuthor()` 返回 `DownloadGroup`（含 `author`、头像 `face`、视频列表）。
- 分组顺序用 `java.text.Collator`（`Locale.CHINA`）按作者名**拼音排序**，空作者名置尾。
- 番剧判断：`BANGUMI_GROUP_LABEL = "番剧·影视"`，命中 `DownloadType.BANGUMI` 的条目全部归入该组，**不**误进「未知 UP 主」。
- `enum DownloadSortMode { DEFAULT, NAME, SIZE }` + `List<DownloadGroup>.applySort(mode)`；`SIZE` 时组间按该组视频总大小排、组内按单文件大小排。

### 3.2 多选（导出记录）
`ui/page/OutListPageSelection.kt` 用 `Set` 维护选中态，Compose 状态驱动顶部「已选 N 项」选择条与批量删除。

### 3.3 稳定性 / 持久化兜底
`common/BiliAuthorRepository.kt` 把扫描结果写入 `DataStore`；`ui/page/AuthorPage.kt` 用 `loaded` 标志区分「已完成一次成功加载」与「空结果」，配合 Shizuku 状态变化，**防止无限重扫**。

### 3.4 给 AI / 开发者的阅读路线
按「数据从哪来 → 怎么加工 → 怎么展示」：
1. 数据来源：`entity/BiliDownloadEntryInfo.kt`、`common/BiliEntryJsonParser.kt`
2. 领域模型 & 分组排序（核心）：`entity/DownloadInfo.kt`
3. 数据聚合：`common/BiliAuthorRepository.kt`
4. 持久化 / 状态：`common/datastore/DataStoreKeys.kt`、`state/AppState.kt`
5. 导出链路：`service/BiliDownService.kt`、`common/BiliDownFile.kt`、`common/BiliDownOutFile.kt`
6. UI：`ui/page/DownloadListPage.kt` → `ui/page/AuthorPage.kt` → `ui/page/OutListPage.kt`
7. 权限：`shizuku/permission/ShizukuPermission.kt`、`common/permission/StoragePermission.kt`

> 每块核心逻辑都有对应单元测试（`app/src/test/`），改完跑 `./gradlew testDebugUnitTest` 验证回归。

## 4 / 工程与发布

- **CI**：GitHub Actions。打 `v*` 标签自动跑单测 → 打 debug 包 → 发布到 Release。
- **dev Barcode / 冒烟**：本地 `export JAVA_HOME=<JDK17>`，`./gradlew assembleDebug`。
- **质量**：`BiliEntryJsonParserTest`、`DownloadInfoTest`、`OutListPageSelectionTest` 等覆盖解析 / 分组 / 排序 / 多选。

## 5 / 与上游的关系（诚实声明）

fork 自 `10miaomiao/bili-down-out`，保留上游版权声明（Apache-2.0）。我没有重造轮子，只是在成熟的导出链路上做「结构视图 + 持久化兜底」的增强。

## 6 / CTA

- **源码 / Star / Issues**：https://github.com/WhyWhatHow/bili-down-out
- **下载**：https://github.com/WhyWhatHow/bili-down-out/releases
- 深入阅读见仓库 `docs/IMPLEMENTATION.md` 与 `docs/MOTIVATION.md`。

欢迎任何技术讨论与贡献。