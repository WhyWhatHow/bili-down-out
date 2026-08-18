# 实现说明（给 AI / 开发者）

> 这份文档回答“**这些功能是怎么实现的**”，并使用对 AI / 开发者友好、可执行的方式描述。
> 如果你想基于这份代码**继续往下改**，请先读 [README](../README.md) 了解全局，再看本文的“阅读路线”和“改哪里”速查表。
> 动机与背景见 [MOTIVATION.md](MOTIVATION.md)。

---

## 1. 整体思路（为什么这样设计）

核心目标是把缓存列表从“一维平铺”升级为“**先分类、可排序、可批量操作**”的结构化视图，并对耗时操作做持久化兜底。

```mermaid
flowchart TB
    Cache[读取离线缓存\nAndroid/data 下缓存目录] --> Parse[解析 entry.json\n识别视频类型 / UP主]
    Parse --> Clean[清洗归类]
    Clean --> Group{分组}
    Group -- 有 UP主 --> ByAuthor[按作者名分组\n中文拼音 Collator 排序]
    Group -- 番剧/影视 --> Bangumi[归入「番剧·影视」组]
    ByAuthor --> Sort[按需排序\nDEFAULT / NAME / SIZE]
    Bangumi --> Sort
    Sort --> Persist[DataStore 持久化扫描结果]
    Persist --> UI[页面展示：分组/头像/排序切换]
    UI --> Export[导出 + 重命名\nBiliDownService]
```

一句话：**清洗归类 → 分组建模 → 可组合排序 → 状态/加载统一管理 → 持久化兜底**。

---

## 2. 分组与排序（核心）

```mermaid
flowchart LR
    A[List<DownloadInfo>] --> B[groupByAuthor]
    B --> C{作者名是否为空}
    C -- 否 --> D[以作者名分组]
    C -- 是但为番剧 --> E[归入 番剧·影视]
    C -- 是且无作者 --> F[暂归 未知 UP主, 排最末]
    D --> G[applySort mode]
    G --> H{DEFAULT}
    G --> I{NAME}
    G --> J{SIZE}
    H --> H1[组按UP主拼音, 组内原序]
    I --> I1[组按UP主拼音, 组内按标题]
    J --> J1[组间按总大小, 组内按大小]
```

### 关键实现点

- **分组**：`entity/DownloadInfo.kt` 里的 `List<DownloadInfo>.groupByAuthor()`，返回 `DownloadGroup`（含 `author`、头像 `face`、视频列表 `videos`）。分组顺序用 `java.text.Collator`（`Locale.CHINA`）按作者名拼音排序，空作者名置尾。
- **番剧分类**：`const val BANGUMI_GROUP_LABEL = "番剧·影视"`，命中 `DownloadType.BANGUMI` 的内容全归入该组，不误进“未知 UP 主”。
- **排序**：`enum DownloadSortMode { DEFAULT, NAME, SIZE }` + `List<DownloadGroup>.applySort(mode)`。`SIZE` 时组间按“该组视频总大小”排、组内按单个文件大小排。

---

## 3. 多选（导出记录）

```mermaid
flowchart LR
    Sel[OutListPageSelection] --> State[Set 维护选中态]
    State --> Bar[顶部选择条: 已选 N 项]
    State --> Batch[批量操作: 删除记录/文件]
    Bar -- 逐项勾选/反选 --> State
```

`ui/page/OutListPageSelection.kt` 用 `Set` 维护选中态，在 Compose 状态中驱动选择条与批量操作。

---

## 4. 稳定性 / 持久化兜底

```mermaid
flowchart TB
    Open[进程重建 / 首开页面] --> Read[先读 DataStore 缓存]
    Read --> Show[立即渲染上次列表]
    Show --> Scan[后台增量重扫]
    Scan --> Update[更新缓存并刷新 UI]
    Update --> Mark[loaded=true\n防止 Shizuku 状态变化触发无限重扫]
```

`common/BiliAuthorRepository.kt` 把扫描结果写入 `DataStore`；`ui/page/AuthorPage.kt` 用 `loaded` 标志区分“已完成一次成功加载”与“空结果”，防止 Shizuku 状态变化触发无限重扫。

---

## 5. 给 AI / 开发者的阅读路线

建议按“数据从哪来 → 怎么加工 → 怎么展示”的顺序读：

1. **数据来源（缓存结构）**：`entity/BiliDownloadEntryInfo.kt`、`common/BiliEntryJsonParser.kt`
2. **领域模型 & 分组排序（核心）**：`entity/DownloadInfo.kt`（`DownloadSortMode` / `groupByAuthor()` / `applySort()`）
3. **数据聚合（仓库层）**：`common/BiliAuthorRepository.kt`
4. **持久化 & 全局状态**：`common/datastore/DataStoreKeys.kt`、`state/AppState.kt`
5. **导出链路**：`service/BiliDownService.kt`、`common/BiliDownFile.kt`、`common/BiliDownOutFile.kt`
6. **UI 层**：`ui/page/DownloadListPage.kt`（首页分组）→ `ui/page/AuthorPage.kt`（按 UP 主）→ `ui/page/OutListPage.kt` + `ui/page/OutListPageSelection.kt`（导出记录多选）
7. **权限**：`shizuku/permission/ShizukuPermission.kt`、`common/permission/StoragePermission.kt`
8. **测试（回归保障 / 行为文档）**：`app/src/test/` 下 `BiliEntryJsonParserTest`、`DownloadInfoTest`、`OutListPageSelectionTest` 等

```mermaid
flowchart LR
    A[1 数据来源\nentry.json 解析] --> B[2 分组排序模型\nDownloadInfo]
    B --> C[3 仓库聚合\nBiliAuthorRepository]
    C --> D[4 持久化/状态\nDataStore + AppState]
    D --> E[5 导出链路\nBiliDownService]
    B --> F[6 UI 页面\nList/Author/OutList]
    F --> G[7 权限\nShizuku/Storage]
    E --> H[8 测试]
```

---

## 6. “改哪里”速查表

如果你/你的 AI 想加一个具体能力，可以直接定位：

| 你想做的事 | 主要改的文件 |
| :-- | :-- |
| 改分组规则 / 新增分组维度 | `entity/DownloadInfo.kt`（`groupByAuthor`） |
| 新增一种排序方式 | `entity/DownloadInfo.kt`（`DownloadSortMode` / `applySort`） |
| 加新的筛选/过滤 | `ui/page/DownloadListPage.kt`（首页）或 `ui/page/AuthorPage.kt` |
| 改导出多选/批量的逻辑 | `ui/page/OutListPageSelection.kt`、`ui/page/OutListPage.kt` |
| 改导出文件命名/拼接 | `common/BiliDownFile.kt`、`common/BiliDownOutFile.kt`、`service/BiliDownService.kt` |
| 改缓存持久化策略 | `common/BiliAuthorRepository.kt`、`common/datastore/DataStoreKeys.kt` |
| 改 Shizuku / 存储权限交互 | `shizuku/permission/ShizukuPermission.kt`、`common/permission/StoragePermission.kt` |

> 每块核心逻辑都有对应单元测试，改完记得跑 `./gradlew testDebugUnitTest` 验证回归。

---

## 7. 给 AI 的一句话指引

> 当你拿到这个仓库，请**先读 [README](../README.md) 建立全局认知**，再依本文第 5 节“阅读路线”按序阅读代码，用第 6 节“速查表”定位要改的位置；改完用 `./gradlew testDebugUnitTest` 验证。本文与源码即为你的上下文，无需外部说明即可继续扩展。