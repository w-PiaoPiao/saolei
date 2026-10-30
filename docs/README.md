# 文档索引

本目录保存审查报告、设计与工程文档。新读者建议先读根目录 [README.md](../README.md)。

## 当前版本（2026-10-27 全量审查 + 修复）

- [review-2026-10-27/README.md](review-2026-10-27/README.md) — 本轮全量审查总报告（问题清单、验证基线、结论）
- [review-2026-10-27/FIX-PLAN.md](review-2026-10-27/FIX-PLAN.md) — 修复计划与协作约定
- [review-2026-10-27/FIXES.md](review-2026-10-27/FIXES.md) — 修复结果汇总（如已生成）
- 分领域报告：
  - [desktop-engine.md](review-2026-10-27/desktop-engine.md) — 桌面引擎/算法
  - [desktop-ui.md](review-2026-10-27/desktop-ui.md) — 桌面 UI / Electron 壳层 / 安全 / 打包
  - [android-core.md](review-2026-10-27/android-core.md) — 安卓 core 引擎
  - [android-app.md](review-2026-10-27/android-app.md) — 安卓 app 层
  - [product-and-release.md](review-2026-10-27/product-and-release.md) — 产品路线图 + README/CI/发布/仓库卫生
- 各领域修复记录：[review-2026-10-27/fixes/](review-2026-10-27/fixes/)（engineering / desktop-engine / desktop-ui / android-core / android-app）
- 截图：[screenshots/](screenshots/)（README 引用）
- [release-checklist.md](release-checklist.md) — 发布前演练清单（版本/构建/CI 标签演练/SHA256/回滚）

## 历史文档（已冻结，仅作记录）

- [audit-2026-10-12.md](audit-2026-10-12.md) — 2026-10-12 全量审查（基于 `461c395`），部分问题已在后续提交修复，勿据此判断当前状态
- [superpowers/specs/2026-07-14-minesweeper-design.md](superpowers/specs/2026-07-14-minesweeper-design.md) — 最初桌面版设计文档，早于无猜/3BV/i18n/安卓端实现

## 约定

- 文件名格式：`audit-YYYY-MM-DD.md`、`review-YYYY-MM-DD/`、`fixes/<area>.md`。
- 历史文档在被取代时会在顶部加「历史快照」横幅，正文保持原样，不追改。
- 外部引用（产品调研/竞品资料）请在文末列出来源 URL，并标注哪些是实际抓取、哪些是未核实的常识。
