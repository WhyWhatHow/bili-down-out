# 【功能提议】缓存导出体验增强：按 UP 主分组视图 + 多维排序 + 导出记录多选 + 稳定性修复

> 项目：BiliDownOut（哔哩缓存导出）
> 关联 fork：https://github.com/WhyWhatHow/bili-down-out（当前版本 v1.6.1）
> 类型：功能提议 / 贡献（欢迎维护者评估）

## 背景

在使用上游 BiliDownOut 导出哔哩哔哩离线缓存时，逐渐遇到几个影响日常体验的点：

1. **缓存一多，列表全靠翻**：没有聚合的手段，几十上百条视频挤在一屏里，找某位 UP 主的视频非常费劲。
2. **UP 主视频与番剧/影视混在一起**：切片类型的内容和 UP 主内容缺乏区分，视觉上很混乱。
3. **首次打开页面偶发一直转圈**：进程被杀重建后，列表需要重新全量扫描，首次加载卡顿甚至无响应。
4. 导出记录的**批量管理与选择**能力偏弱。

因此我在上游 master 分支基础上做了一轮增强，希望一方面解决这些痛点，另一方面把实现的思路和方式整理出来，供项目参考、评估是否值得合并回主线。

## 一、新增功能概览

- **按 UP 主分组浏览**：缓存列表自动按 UP 主聚合展示，每个分组带 UP 主头像。
- **番剧/影视独立分组**：没有 UP 主概念的番剧、影视内容统一归入「番剧·影视」分组，不再误混入「未知 UP 主」，也不会污染 UP 主分组。
- **多维排序**：支持「默认（按 UP 主拼音）」「按名称」「按大小」三种模式；按大小时组与组之间也按该 UP 主缓存总大小排序。
- **导出记录多选操作**：在导出记录页支持多选与批量处理（配合新的选择页管理选中态）。
- **稳定性修复**：对列表扫描结果做持久化缓存，根治「进程被杀后首次打开 UP 页面一直转圈」的问题。

## 二、所做的工作与修改

主要涉及的代码改动（相对上游 master）：

- **新增页面**：`ui/page/AuthorPage`（按 UP 主分组页）、`ui/page/OutListPageSelection`（导出记录多选页）。
- **新增数据层**：`common/BiliAuthorRepository`（负责 UP 主信息 / 头像 / 列表扫描的数据聚合）。
- **核心重写**：`ui/page/DownloadListPage`（首页列表，分组 + 排序 + 加载态 + Shizuku 授权引导）、`entity/DownloadInfo`（分组与排序模型）、`ui/page/OutListPage`（导出记录页）。
- **服务与导出**：`service/BiliDownService`、`common/BiliDownFile`、`BiliDownOutFile`、`FileNameInputDialog`、`RecordItem` 等配套调整。
- **持久化**：`BiliAuthorRepository` 结合 `DataStore` 做列表缓存落盘；`AppState` 增加对扫表进度的全局状态管理。
- **测试**：新增约 1100 行单元测试，覆盖 `entry.json` 解析、按作者分组与排序、多选状态、导出文件命名等关键逻辑。

## 三、整体修改逻辑 / 基本逻辑

核心思路可以概括为「**把缓存列表从『一维平铺』升级为『先分类、可排序、可批量操作』的结构化视图**」：

1. **数据清洗与归类**：解析每一个离线缓存的 `entry.json`，识别出视频类型（普通/番剧）与 UP 主信息。
2. **分组建模**：以 UP 主名称为 key 分组，番剧类归入固定分组；分组顺序使用中文 Collator 按拼音排序，未知 UP 主排最后。
3. **排序可组合**：分组结构固定，排序作用于「组内」与「组间」两类维度，互不破坏分组语义。
4. **状态与加载**：用 Presenter（`rememberPresenter`）驱动 UI 状态，明确区分加载中 / 成功 / 空结果 / 失败 / Shizuku 未授权等情形，避免无限重扫。
5. **稳定性兜底**：扫描结果先落 `DataStore`，进程重建时先读缓存再增量刷新，保证首屏即时可用。

## 四、具体实现方式

- **分组**：新增 `List<DownloadInfo>.groupByAuthor()`，返回 `DownloadGroup`（含 UP 主名、头像 `face`、视频列表 `videos`）；分组顺序用 `java.text.Collator`（`Locale.CHINA`）对作者名做拼音排序，空作者名置尾。
- **番剧分类**：用 `const val BANGUMI_GROUP_LABEL = "番剧·影视"` 作为固定分组名，命中 `DownloadType.BANGUMI` 的内容统一归入该组，避免误归入「未知 UP 主」。
- **排序**：`enum DownloadSortMode { DEFAULT, NAME, SIZE }` + `List<DownloadGroup>.applySort(mode)`；`SIZE` 模式下组间按「该组视频总大小」排序，组内按单个文件大小排序，便于快速找到占用空间最大的内容。
- **多选**：`OutListPageSelection` 维护一组选中态集合（`Set`），通过在 Compose 状态中驱动选择条与批量操作（删除记录及文件等）。
- **缓存持久化**：`BiliAuthorRepository` 把列表扫描结果写入 `DataStore`；`AuthorPage` 用 `loaded` 标志位区分「已完成一次成功加载」与「空结果」，防止 Shizuku 状态变化时触发无限重扫。

## 五、测试情况

以上逻辑均附有 JUnit 单元测试（位于 `app/src/test`），包括：

- `BiliEntryJsonParserTest`：解析不同结构的 `entry.json`。
- `DownloadInfoTest`：按作者分组、番剧归类、三种排序模式的分组与排序正确性（含中文拼音顺序）。
- `OutListPageSelectionTest`：多选状态机的增删与批量操作。
- `BiliAuthorRepositoryTest`、`BiliDownOutFileTest`：仓库层与命名逻辑。

## 六、协作意愿与后续

- **初衷**：本 fork 无意分流上游社区。作者的诉求是先把体验做好，并愿意把其中通用、可复用、与上游无冲突的能力**贡献回主线**。
- **关于 PR 拆分**：由于当前改动横跨多个页面与数据层，一次 PR 提交过大、Review 成本高。因此先以本 issue 说明功能与实现方式，供维护者判断方向；如果维护者认可，我可将改动按「分组视图」「排序」「多选」「稳定性」等主题**拆分为多个小而聚焦的 PR** 逐一提上来。
- **可审阅产物**：完整实现与构建产物在 https://github.com/WhyWhatHow/bili-down-out 的 `v1.6.1` 标签下可查看；打 `v*` 标签会自动触发 CI（单测 → debug 包 → Release）。

期待维护者的反馈，也欢迎任何方向上的意见。