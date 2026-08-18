# [分享] 我做了一个把 B 站缓存干净导出的增强分支

> V2EX · 技术论坛 · 低调务实 · 谦逊帖。由 `docs/promo/article-devs.md` 改稿。

---

论坛的朋友们，分享一个自己维护的开源小项目。

**背景**：B 站 APP 的离线缓存存在 `Android/data/tv.danmaku.bili/download/` 深处，普通文件管理器进不去，出来是一堆 `entry.json` + `blv` 碎片。上游 [10miaomiao/bili-down-out](https://github.com/10miaomiao/bili-down-out) 解决了「读取并合并导出」，我作为用户遇到三个不便：查找慢、不能多选、不能按 UP 主挑视频。

**我做了什么**：在上面这个想法成熟方案的基础上，做了一个增强分支，把缓存列表从「一维平铺」升级为「按 UP 主分组 + 番剧独立 + 多维排序 + 导出记录多选」的结构化视图，并对耗时扫描做持久化兜底（进程被杀首开不转圈）。技术栈 Kotlin + Compose (Material3) + Room + DataStore + Shizuku，`minSdk 21 / targetSdk 35`。

**当前状态**：稳定版 `v1.6.1`，有配套 CI（打 tag 自动单测 + 打 debug 包 + 发布 Release）和一批单元测试。

**下载 / 源码 / Issues**：https://github.com/WhyWhatHow/bili-down-out

几点想说的：
- 这是个人兴趣项目，仅供学习与测试，记得是 **debug 包**。
- Android 13+ 访问 `Android/data` 需要配合 Shizuku（免 root）。
- 我**没有**重造轮子，功劳绝大部分属于原作者，我只是把「查找、挑选、批量」这几个环节做顺手了。

如果觉得对你有用，去仓库点个 Star 就是最大的支持；也欢迎提 Issues 或者指出实现里的问题。谢谢。