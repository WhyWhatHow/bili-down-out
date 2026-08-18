# 分享一个可定制的 BiliDownOut 增强版 fork（按 UP 主分组 + 多维排序 + 导出多选 + 稳定性修复）

> 项目：BiliDownOut（哔哩缓存导出）
> fork 仓库：https://github.com/WhyWhatHow/bili-down-out（当前版本 v1.6.1）
> 目的：把一套比上游更完整的实现公开出来，供有同类需求的人**在此基础上做自己的定制**。

## 为什么冒出来

在使用上游 BiliDownOut 导出哔哩哔哩离线缓存时，越来越觉得一屏平铺的列表不好用：

1. 缓存一多就全靠翻，找某位 UP 主的视频很费劲；
2. UP 主视频和番剧/影视混在一起，视觉混乱；
3. 进程被杀重建后，首次打开页面偶发一直转圈；
4. 导出记录缺少批量选择的便利。

因此我在上游 master 基础上做了一轮增强，并**完整开源**。如果你也有类似的需求，可以直接把这份代码交给你的 AI / 开发者作为**起点**去读、去按你自己的口味改，而不必从零开始。

> 说明：这不是“推销某个二进制”，而是“分享一套可复用、可直接读懂的实现”。仓库已开源，打 `v*` 标签会自动构建并发布 debug 包。

## 一、新增了哪些功能

- **按 UP 主分组浏览**：缓存列表自动按 UP 主聚合，分组带 UP 主头像，按中文拼音排序。
- **番剧 / 影视独立分组**：没有 UP 主的番剧归入「番剧·影视」组，不再混进 UP 主内容。
- **多维排序**：默认（UP 主拼音）/ 按名称 / 按大小（组间也按该 UP 主总大小排序，便于找出最占空间的内容）。
- **导出记录多选**：导出记录页支持多选与批量处理。
- **稳定性修复**：列表扫描结果持久化缓存，根治“进程被杀后首次打开一直转圈”。

## 二、做了哪些工作与修改

- **新增页面**：`ui/page/AuthorPage`（按 UP 主分组页）、`ui/page/OutListPageSelection`（多选页）。
- **新增数据层**：`common/BiliAuthorRepository`（UP 主信息 / 头像 / 列表扫描的数据聚合）。
- **核心重写**：`ui/page/DownloadListPage`（分组 + 排序 + 加载态 + Shizuku 授权引导）、`entity/DownloadInfo`（分组与排序模型）、`ui/page/OutListPage`（导出记录页）。
- **配套调整**：`service/BiliDownService`、`common/BiliDownFile` / `BiliDownOutFile`、`FileNameInputDialog`、`RecordItem` 等。
- **持久化**：`BiliAuthorRepository` 结合 `DataStore` 落盘列表缓存；`AppState` 增加全局扫表进度状态。
- **测试**：新增约 1100 行单元测试，覆盖 entry.json 解析、分组排序、多选、文件命名等。

## 三、整体修改逻辑 / 基本逻辑

核心是把列表从“一维平铺”升级为“**先分类、可排序、可批量操作**”的结构化视图：

1. **清洗归类**：解析每个离线缓存的 `entry.json`，识别视频类型（普通/番剧）与 UP 主。
2. **分组建模**：以 UP 主名称为 key 分组，番剧归入固定组；用中文 Collator 按拼音排序，未知 UP 主放最末。
3. **排序可组合**：分组结构固定，排序作用于“组内”与“组间”两个维度，不破坏分组语义。
4. **状态与加载**：用 Presenter 驱动 UI 状态，明确区分加载中 / 成功 / 空 / 失败 / Shizuku 未授权，避免无限重扫。
5. **稳定性兜底**：扫描结果先落 DataStore，进程重建先读缓存再增量刷新，保证首屏即时可用。

## 四、功能的具体实现方式

- **分组**：`List<DownloadInfo>.groupByAuthor()` 返回 `DownloadGroup`（含 `author`、头像 `face`、`videos`）；顺序用 `java.text.Collator`（`Locale.CHINA`）按作者名拼音排序，空作者名置尾。
- **番剧分类**：`const val BANGUMI_GROUP_LABEL = "番剧·影视"`，命中 `DownloadType.BANGUMI` 的内容全归入该组，避免误进“未知 UP 主”。
- **排序**：`enum DownloadSortMode { DEFAULT, NAME, SIZE }` + `List<DownloadGroup>.applySort(mode)`；`SIZE` 下组间按“该组视频总大小”排、组内按单个文件大小排。
- **多选**：`OutListPageSelection` 用 `Set` 维护选中态，在 Compose 状态里驱动选择条与批量操作。
- **缓存持久化**：`BiliAuthorRepository` 把列表扫描结果写入 `DataStore`；`AuthorPage` 用 `loaded` 标志区分“已完成一次成功加载”与“空结果”，防止 Shizuku 状态变化触发无限重扫。

## 五、测试

`app/src/test` 下均带 JUnit 单测：`BiliEntryJsonParserTest`（解析）、`DownloadInfoTest`（分组/番剧归类/三种排序，含中文拼音顺序）、`OutListPageSelectionTest`（多选状态机）、`BiliAuthorRepositoryTest`、`BiliDownOutFileTest`（命名）。

## 六、你可以怎么用

- **想直接用**：下载 `v1.6.1` 的 APK 安装即可。
- **想自己定制**：直接 `git clone https://github.com/WhyWhatHow/bili-down-out.git`，让 AI 或你自己读上面的代码逻辑，在其基础上加你的功能（改排序、加过滤、改导出格式……）。这套实现刻意把“分组 / 排序 / 多选 / 持久化”拆成了清晰可读的模块，方便不是原作者的人去理解和改动。
- **想了解取舍**：文档里每一节都对应可定位的源码位置，可以直接边看 issue 边翻代码。

如果这份实现对你或你的 AI 有用，仓库右上角的 **Star** 就是对我的认可。也欢迎在你的定制基础上做交流，但请保留对上游项目与作者的致谢。