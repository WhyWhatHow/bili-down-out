#!/usr/bin/env bash
# 按提交 hash 前缀映射到精确的中文提交信息（body 附主要变更文件）
c="${GIT_COMMIT:-}"
msg=""
case "$c" in
  edcbaf7*) msg="chore: 初始化工程与 CI 基线（Gradle/CI/图标/许可）" ;;
  5878272*) msg="feat(ui): 重构导出记录页布局并同步构建配置" ;;
  5d77ebc*) msg="feat(ui): 导出记录页细节调整" ;;
  3343cec*) msg="feat: 导出记录支持多选与批量导出（入队+记录联动）" ;;
  de4500c*) msg="feat: 新增界面改版原型 ui-redesign" ;;
  7e3f270*) msg="feat: 原型 ui-redesign 细化" ;;
  e100864*) msg="chore: 更新原型压缩包（二进制）" ;;
  d1f996a*) msg="feat(ui): 调整主题配色/明暗与字体（Theme/Color/Type）" ;;
  0cff648*) msg="feat(ui): 微调列表项/底栏/进度页组件样式并更新构建" ;;
  9bb888d*) msg="feat: 引入按 UP 主分组模型并适配作者页" ;;
  e14a37b*) msg="feat: 分组排序细节调整" ;;
  0e611ea*) msg="feat(ui): 扩充 UP 作者页功能" ;;
  3078898*) msg="test: 补充列表/多选/导出相关单元测试并更新构建" ;;
  893ef5e*) msg="chore: 更新原型压缩包（二进制）" ;;
  eb0939b*) msg="docs: 更新 README 并新增上游 issue 说明" ;;
  c09cac6*) msg="docs: 修订上游 issue 说明" ;;
  494c866*) msg="docs: 扩展上游 issue 的实现与阅读说明" ;;
  4ac4a1f*) msg="docs: 补全 README 与实现/动机文档" ;;
  678bccf*) msg="chore: 新增提交信息钩子与 AGENTS 提交规范" ;;
  1cbd258*) msg="chore: gitignore 忽略构建产物" ;;
  *) msg="" ;;
esac
if [ -n "$msg" ]; then
  echo "$msg"
  echo
  echo "变更："
  git --no-pager diff-tree --no-commit-id --name-only -r "$c" 2>/dev/null | sed '/^$/d' | sed 's/^/- /'
else
  git cat-file -p "$c" | sed -n '/^$/,$p' | sed '1d'
fi
exit 0