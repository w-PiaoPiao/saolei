# 发布前演练清单（Release checklist）

适用版本：1.3.0+（VERSION 单一版本源、`desktop-v*` / `android-v*` 双标签、CI 自动发布）。

本清单用于**每次对外发布前**在本地或 fork 上完整演练一遍；正式发布只差最后一步打标签。
真实仓库打标签前至少完成 A–C 三轮。

## A. 版本与文档一致性（本地，2 分钟）

- [ ] `VERSION` 已更新为目标版本（如 `1.3.0`），且 `package.json.version` 与之一致
      （`npm version X.Y.Z --no-git-tag-version --allow-same-version` 可自动同步 package-lock）。
- [ ] `CHANGELOG.md` **最前面**新增 `## [X.Y.Z] - YYYY-MM-DD` 段（最新版本在最前，CI 取首条）。
- [ ] `node scripts/check-version.mjs` 通过（本地/PR 模式：只校验内部一致性）。
- [ ] 模拟发布标签：
      `GITHUB_REF_NAME=desktop-vX.Y.Z node scripts/check-version.mjs --expect-prefix desktop-v`
      `GITHUB_REF_NAME=android-vX.Y.Z node scripts/check-version.mjs --expect-prefix android-v`
      两条都应通过；错误前缀 / 版本不一致应 exit 1。
- [ ] README（中英两版）与 `docs/README.md` 中没有过时的版本号/下载表；
      `docs/screenshots/` 引用的图片存在。

## B. 代码与构建验证（本地，10–20 分钟）

桌面：

- [ ] `npm ci && npm test` 全绿。
- [ ] `npm run lint` 0 error、`npm run format:check` 通过。
- [ ] `npm run pack`（electron-builder --dir）成功，启动 `npx electron .` 冒烟；
      无猜首点、无猜降级提示、暂停、i18n 切换、统计写入各点一遍。
- [ ] 打包产物内 worker 可用：asarUnpack 的 `src/generator-worker.js`/`generator.js`/`solver.js`/`config.js` 均在
      `app.asar.unpacked/src/` 下（历史审查修复过路径替换，回归必查）。

安卓：

- [ ] `cd android && ./gradlew :core:test --console=plain` 全绿。
- [ ] `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 成功。
- [ ] 有签名配置时 `./gradlew :app:assembleRelease` 成功，并用 `apksigner verify --print-certs` 确认
      签名证书与 `CN=Saolei` 一致、版本号正确。
- [ ] KPA 真机冒烟：手柄全部键位、触摸对话框、暂停/恢复、无猜生成、旋转/切后台不丢局。

## C. CI 演练（fork 或临时分支，30–60 分钟）

- [ ] 仓库 secrets 已配置：`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` /
      `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`（缺失时 release job 会失败并给出明确错误，这是预期行为）。
- [ ] 仓库 Actions 默认权限允许 job 级提升 `contents: write`（Settings → Actions → General → Workflow permissions）。
- [ ] 推送 Desktop 演练标签：`git tag desktop-vX.Y.Z && git push origin desktop-vX.Y.Z`
      （在 fork 上用同一版本号即可；不要与正式仓库冲突）。
  验证顺序：
  1. `Test, lint & version check` job：版本校验、lint、format、单测全绿；
  2. `Package macos/windows/linux` 三平台打包成功，`dist/SHA256SUMS-<os>.txt` 生成；
  3. `Publish checksums & release notes` job：Release 中出现 3 个安装包 + `SHA256SUMS.txt`；
     发布说明来自 CHANGELOG 最新段；Release 不是 draft、tag 正确；
  4. 下载安装包与 `SHA256SUMS.txt`，本地 `shasum -a 256 -c SHA256SUMS.txt` 校验（从 Release 下载的文件名校验）。
- [ ] 推送 Android 演练标签：`git tag android-vX.Y.Z && git push origin android-vX.Y.Z`。
  验证顺序：
  1. 版本校验通过（标签必须是 `android-vX.Y.Z`，且与 VERSION 一致）；
  2. `:core:test` / `:app:testDebugUnitTest` / `:app:lintDebug` / `assembleDebug` 全绿；
  3. `Signed release & GitHub Release` job 产出签名 `app-release.apk` + `SHA256SUMS-android.txt`
     并上传到同名 Release；
  4. 下载 APK 用 `apksigner verify --print-certs` 与 `aapt dump badging` 复核签名与 versionName/versionCode
     （`android-v1.3.0` → `1.3.0` / `10300`）。

## D. 未签名/未公证声明（桌面）

- [ ] 确认 Release 说明或 README 已声明：桌面安装包**未代码签名、未公证**，macOS Gatekeeper /
      Windows SmartScreen 首次启动可能提示；这是当前已知限制，不是 CI 失败。
- [ ] 若配置了签名证书（`CSC_LINK`/`CSC_KEY_PASSWORD`），需同步更新 README 与 CI（本仓库默认不签名）。

## E. 发布后与回滚

- [ ] Release 页面附件齐全：桌面 `minesweeper-<ver>-mac-arm64.dmg` / `-win-x64.exe` / `-linux-x64.AppImage` /
      `SHA256SUMS.txt`；安卓 `app-release.apk` / `SHA256SUMS-android.txt`。
- [ ] `VERSION`、`package.json`、CHANGELOG、Release tag 四者一致；应用内「关于」页显示正确版本。
- [ ] 若发布有误：先删除 Release（保留 tag 便于修复重发）或两者一并删除；
      重打标签前务必确认版本号已 bump，禁止复用同一 tag 发不同内容。
- [ ] 密钥安全：`android/keystore/` 与备份**始终在仓库目录外**（`~/saolei-secrets-backup/`，700/600）；
      若怀疑泄露，立即停止发布、生成新密钥并在 README/secrets 更新（旧密钥无法续发，需评估用户覆盖安装影响）。

## F. 已知不在本清单范围

- Google Play / F-Droid 上架材料（AAB、隐私政策、商店截图、Data safety）——见
  [review-2026-10-27/product-and-release.md](review-2026-10-27/product-and-release.md) P0-2。
- Electron/electron-builder 大版本升级——单独 PR，升级后必须重跑本清单 A–C。
