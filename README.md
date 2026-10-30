# Minesweeper 🎯

[中文](README.zh-CN.md) | **English**

A classic Windows XP-style Minesweeper — a cross-platform desktop app for macOS · Windows · Linux,
plus a native Android port for the 960×640 landscape handheld **Konkr KPA** (攻氪 KPA).

- [Features](#features) · [Screenshots](#screenshots) · [Quick start](#quick-start)
- [Controls](#controls-desktop) · [Android](#android-konkr-kpa) · [Architecture](#architecture)
- [Release & signing](#release--signing) · [Known limitations](#known-limitations) · [License](#license)

---

## Features

### Gameplay

| Feature | Details |
|---|---|
| Three preset difficulties | Beginner 9×9/10, Intermediate 16×16/40, Expert 16×30/99 |
| Custom boards | Rows/columns 9–50, mine count up to 85% of the cells (both desktop and Android) |
| Readable huge boards | Cell size has a readability floor; boards that don't fit scroll instead of shrinking |
| Safe first click | 3×3 safe zone plus a guaranteed zero-cell flood reveal |
| No-guess mode | Boards are generated so they can be solved by pure logic; if the attempt/time budget is exhausted the HUD explicitly warns that the board may require guessing (very large boards can hit this) |
| Non-blocking no-guess generation | Runs on a background worker/thread; the first click never freezes the UI and shows a "generating…" hint |
| Chording | Double-click, middle-click, or press left+right on a revealed number (desktop); B / tap on a revealed number (Android) |
| Flag → question mark → clear | Right click (desktop) or the flag button (Android) cycles flag/question/clear |
| Endgame feedback | Wrong flags are crossed out on a loss, remaining mines are auto-flagged on a win, and a "new record" banner appears when you beat the best time |

### Quality of life

| Feature | Details |
|---|---|
| Restart protection | Restarting mid-game asks for confirmation first, defaulting to Cancel |
| Pause | Auto-pause when the window loses focus or a dialog is open (desktop); Android pauses automatically when backgrounded or when a dialog is open |
| Full keyboard control | All **gameplay** actions are reachable from the keyboard, with a keyboard cursor (diagonals supported); menu/dialog entries are mouse- or menu-key driven |
| Rebindable keys | Gameplay shortcuts can be rebound and are persisted; Android also has an on-screen key-test row and can rebind the menu button (Back is reserved) |
| Single instance | Relaunching focuses the existing window instead of opening a second one (desktop) |
| System theme following | Follows the system light/dark setting until a theme is picked manually |
| Languages | Chinese / English UI; defaults to the system language, can be switched in the app, and persists |

### Statistics

| Feature | Details |
|---|---|
| Game statistics | Games played/won/lost, win rate, current/best streak, best time per difficulty, total play time |
| Custom-board buckets | Custom boards are tracked separately by board size (`rows×cols/mines`) instead of all sharing one bucket |
| 3BV efficiency | Best 3BV/s per bucket plus a chart of the last 20 wins with an average line |
| Fair records | Paused games are recorded in history but never set best-time/3BV-s records; games won by flags count for best time but never update the 3BV/s best |
| Millisecond timing | Records keep millisecond durations; the LED display is capped at 999 s like the classic game |
| Unit tests | Built in with `node:test` — engine, solver, generator, stats, keybindings, movement, cross-platform parity and UI helpers (`npm test`) |

### Appearance and platform

- **Six themes** (light, dark, WinXP silver, Vista blue, macOS, macOS dark) with the preference persisted;
  system light/dark is followed until a theme is chosen manually.
- **Six synthesized sound effects** (click, flag, chord success/fail, explosion, win) generated at runtime —
  no external audio files. Mute is available from the menu.
- **Accessibility**: cells expose labels, dialogs are keyboard-operable, and animations respect the
  reduce-motion preference (desktop).
- **Window comfort** (desktop): window size/position are remembered, zoom controls are available, and a
  tray icon lets the app stay available in the background.
- **Performance**: the desktop uses incremental dirty-cell rendering; Android caches the static board
  layer in a reusable bitmap and only repaints changed cells, with per-cell animation skipped for large
  flood reveals.

---

## Screenshots

| Light theme | Dark theme |
|---|---|
| ![Desktop, light theme](docs/screenshots/desktop-light.png) | ![Desktop, dark theme](docs/screenshots/desktop-dark.png) |

---

## Quick start

Requirements: **Node.js ≥ 22** (`.nvmrc` is provided) and npm.

```bash
npm install
npm start
```

## Tests and lint

```bash
npm test          # node:test unit tests (engine / solver / generator / stats / keybindings / movement / parity / UI helpers)
npm run lint      # ESLint (flat config, minimal rule set)
npm run format:check
node scripts/check-version.mjs   # VERSION ↔ package.json ↔ CHANGELOG consistency
```

## Build (desktop)

```bash
npm run dist:mac     # macOS .dmg (Apple Silicon on the CI runner)
npm run dist:win     # Windows x64 portable .exe
npm run dist:linux   # Linux x64 .AppImage
```

Artifacts are written to `dist/` with ASCII names
(`minesweeper-<version>-<os>-<arch>.<ext>`); the product display name remains 扫雷.

> CI builds on `macos-latest` (Apple Silicon), `windows-latest` (x64) and `ubuntu-latest` (x64, so the
> Linux artifact is **x64**). Intel-Mac and Linux-arm64 packages are not built by CI. Builds are
> **not code-signed or notarized**, so macOS Gatekeeper / Windows SmartScreen may warn on first launch
> (see [Known limitations](#known-limitations)).

## Releases / download

Releases are published to GitHub Releases by CI:

| Platform | Format | Notes |
|---|---|---|
| macOS (Apple Silicon) | `.dmg` | Built on `macos-latest`; unsigned/not notarized |
| Windows (x64) | `.exe` | Portable, double-click to run; unsigned |
| Linux (x64) | `.AppImage` | `chmod +x` then run; built on `ubuntu-latest` |

Each release also includes a `SHA256SUMS.txt`. Desktop releases use `desktop-v*` tags; Android releases
use `android-v*` tags (see [Release & signing](#release--signing)). The running version is shown in the
app's About dialog.

---

## Controls (desktop)

| Input | Action |
|---|---|
| Mouse left | Reveal a cell; on a revealed number it chords |
| Mouse right | Flag → question mark → clear |
| Double-click / middle-click / left+right | Chord on a revealed number |
| Arrow keys | Move the keyboard cursor (diagonals supported, hold to repeat) |
| Space / Enter (default) | Reveal or chord at the cursor |
| F (default) | Flag at the cursor |
| F2 (default) | New game (asks for confirmation mid-game) |
| Esc | Close the current dialog |

The four movement keys, reveal/chord, flag and new-game keys are rebindable from
**Game → Keyboard shortcuts…** (the current bindings are shown in the dialog). F2 is a keyboard
shortcut, not a native menu accelerator.

---

## Android (Konkr KPA)

A native port for the 960×640 landscape Android handheld **Konkr KPA** (攻氪 KPA; D-pad + ABXY).
The code lives in `android/`.

- **Native Kotlin** — custom `View` (Canvas) rendering, immersive fullscreen, no browser layer.
- **Fullscreen layout** — the app paints the whole 960×640 screen with the window panel colour (no desktop
  backdrop left around the board); the board scales to the available height (beginner cells grow from
  48 px to ~56 px) and keeps a classic 2 px sunken frame.
- **Status bar** — the bottom bar shows the current time (follows the system 12/24-hour setting) and the
  battery level (icon + percentage; green with a bolt while charging, red at ≤ 20%).
- **Ported engine** — `android/core` is a pure-JVM module that ports the desktop
  `game.js` / `solver.js` / `movement.js` / `keybindings.js` / `stats.js` logic, covered by JUnit tests.
- **Gamepad controls** — D-pad to move (diagonals and hold-to-repeat supported), B reveals/chords,
  A flags (flag → question mark → clear), Start starts a new game, Select opens the menu.
- **Rebindable keys** — Menu → Key bindings; any non-reserved key can be rebound (with an on-screen
  key-test row) and is persisted. Back is reserved for closing dialogs.
- **No-guess mode** — a menu toggle with an adaptive attempt/time budget; generation runs on a
  background thread and the HUD shows "generating a no-guess board…". If the budget is exhausted, the
  last layout is reused and "⚠ no-guess protection unavailable" is shown in the HUD instead of
  degrading silently.
- **3BV efficiency stats and chart** — best 3BV/s per bucket; Menu → Efficiency chart plots the last
  20 wins with an average line (left/right or the on-screen arrows switch difficulty).
- **Mis-touch protection and endgame feedback** — restarting mid-game asks for confirmation first
  (defaulting to Cancel); wrong flags get a red cross on loss; a record shows a "New record!" banner.
- **Touch support** — every dialog is touch-operable (menu items tap to activate; the custom-board
  dialog taps a row to select, taps ◀▶ to adjust, and has explicit OK/Cancel buttons; Stats/About close
  on tap; the chart switches difficulty via arrows; key bindings enter recording when tapped).
  Difficulty buttons in the bottom bar are directly tappable (with the same restart confirmation),
  pressing a revealed number previews the chord area, and huge boards scroll by touch drag or gamepad
  cursor movement.
- **State restore** — the current game is saved on pause/configuration changes and restored after the
  process is recreated, so a background kill doesn't lose the board.
- **Languages** — the Android UI follows the system language (Chinese/English string resources).
- **Accessibility** — TalkBack exposes the game view as a single accessible control with activate/flag/new-game/menu actions and announces state changes (summary-level integration, not per-cell virtual nodes).
- **Readable huge boards** — cell edge floor of 20 px, digits at 60% of the cell, bevel width scaling
  with the cell.
- **System light/dark following** — follows the system setting until a theme is picked from the menu.
- **Version** — read from the repository `VERSION` file by Gradle; CI overrides it from the release tag.
- **Otherwise aligned with the desktop version** — three difficulties plus custom boards, 6 themes,
  6 runtime-synthesized sound effects, statistics, auto-pause, safe first click, shake on a failed chord.

### Build and install (Android)

```bash
cd android
./gradlew :app:assembleDebug          # produces app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Troubleshooting: if the project path contains non-ASCII characters (e.g. a Chinese directory name)
> and Kotlin compilation fails or paths are escaped as `uXXXX`, copy `android/` to a plain ASCII path
> and build there (GitHub CI is unaffected).

On the KPA, enable "Developer options → USB debugging" first. Debug builds can be sideloaded directly;
for release builds use `assembleRelease` with a signing config (R8 shrinking and obfuscation are enabled
for release, bringing the signed APK down to roughly 0.3 MB from about 2.2 MB for debug).

### Controls (Android)

| Button | Action |
|---|---|
| D-pad | Move the cursor (diagonals supported, hold to repeat) |
| B | Reveal a cell; on a revealed number it chords |
| A | Flag a cell (flag → question mark → clear) |
| Start | New game (asks for confirmation mid-game) |
| Select | Menu (new game / difficulty / custom board / theme / mute / no-guess / key bindings / stats / efficiency chart / about / exit) |
| Back | Close a dialog / open the menu |

Touch (optional): tap to reveal or chord, long-press to flag, tap the face to start a new game, drag to
browse huge boards. Every dialog is touch-operable — menu items tap to activate; in the custom-board
dialog, tap a row to select it, tap ◀▶ to adjust the value and tap OK/Cancel to submit; Stats and About
close on tap; the efficiency chart switches difficulty via its arrows; key bindings enter recording
when a row is tapped. Tapping outside a dialog acts like Back.

---

## Architecture

```
.
├── main.js / preload.js       Electron main process: window, menu, worker-based no-guess generation,
│                              single instance, IPC, tray & window-state persistence
├── src/                       Renderer (vanilla JS + CSS3)
│   ├── game.js                Game engine (board, flood fill, 3BV, terminal states), DOM-free
│   ├── generator.js           Mine placement (plain / no-guess) — injectable RNG and clock
│   ├── solver.js              Single-point / subset / global deduction solver (no-guess guarantee)
│   ├── stats.js               Statistics + per-bucket history (localStorage via injectable storage)
│   ├── keybindings.js         Rebindable keys (injectable storage)
│   ├── movement.js            Keyboard diagonal movement composition
│   ├── i18n.js                zh/en dictionary + `t()`/`setLanguage()` (shared with the main process)
│   ├── neighbors.js           Shared neighbour iteration helpers
│   ├── ui.js / app.js         Rendering, dialogs, timer, keyboard/mouse flow
│   └── audio.js               Web Audio synthesized sound effects
├── android/
│   ├── core/                  Pure-JVM engine: GameEngine, Solver, StatsManager, KeyBindings, Synth…
│   └── app/                   Native Android app: MainActivity + custom View (Canvas) + resources
├── scripts/                   CI/maintenance scripts: check-version / sha256sums / release-notes / clean
├── test/                      node:test unit tests (desktop)
├── VERSION                    Single source of truth for the version number
└── docs/                      Reviews, design/engineering docs and screenshots
```

Key ideas:

- The mine generator and solver are pure functions shared between the renderer, the main process
  (worker thread) and Node tests; they accept injected RNG/clock for deterministic behaviour.
- The desktop renderer uses dirty-cell tracking (`markDirty`/`markAllDirty`) for incremental rendering;
  Android mirrors this with a static-layer bitmap cache and dirty-region repaints.
- Android `core` deliberately has no Android dependencies (only the `Storage` interface), so the whole
  engine runs under plain JVM JUnit tests.
- All user data is local: desktop uses `localStorage`, Android uses `SharedPreferences`.

## Development

- Toolchain: Node.js ≥ 22 (`.nvmrc`), Electron 45 + electron-builder 26 for the desktop build, Java 17
  for Android, Gradle wrapper included (Android Gradle Plugin 8.5 / Kotlin 1.9).
- Style: ESLint flat config (`eslint.config.mjs`, minimal rules), Prettier for scripts/config files,
  `.editorconfig` for editors. The lint/format gates are intentionally incremental to avoid touching
  unrelated legacy files.
- Tests: `npm test` (desktop) and `cd android && ./gradlew :core:test` (Android core).
- CI: every PR runs desktop tests/lint/version checks and Android `:core:test` + `:app:testDebugUnitTest`
  + `:app:lintDebug` + `assembleDebug`.
- Documentation index: [`docs/README.md`](docs/README.md). Historical reviews live under `docs/` with a
  "historical snapshot" banner; the current review is
  [`docs/review-2026-10-27/README.md`](docs/review-2026-10-27/README.md).

## Release & signing

**Single version source.** The repository-root `VERSION` file is the only source of truth.
`package.json.version`, the first numeric entry in `CHANGELOG.md`, and the release tag must match it.
`scripts/check-version.mjs` enforces this in CI (and locally).

**Tag semantics.**

- Desktop releases: `desktop-vX.Y.Z` (e.g. `desktop-v1.3.0`) → `.github/workflows/build.yml`.
- Android releases: `android-vX.Y.Z` (e.g. `android-v1.3.0`) → `.github/workflows/android.yml`.
- The historical `vX.Y.Z` tags (`v1.0.0` … `v1.2.1`) are **frozen for archive purposes only** and no
  longer trigger releases.

**Desktop CI.** Pushing a `desktop-v*` tag runs tests, lint and the version check, then builds
macOS/Windows/Linux packages. electron-builder publishes them to GitHub Releases; a follow-up job merges
per-platform `SHA256SUMS-<os>.txt` into one `SHA256SUMS.txt` and writes release notes from the latest
CHANGELOG section. Pull requests and `main` pushes only run the quality gates, not packaging.

**Android CI.** PRs and `main` pushes run tests, lint and a debug APK. Pushing an `android-v*` tag builds
a signed release APK; the repository must define these secrets:

`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`.

The release version is injected from the tag (`android-v1.2.3` → `versionName=1.2.3`,
`versionCode=10203`); a tag that is not `android-vX.Y.Z` aborts the release instead of silently falling
back to the default version. The APK and `SHA256SUMS-android.txt` are attached to the GitHub Release.

**Signing locally.**

```bash
cd android
keytool -genkeypair -v -keystore keystore/release.keystore -alias saolei \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass YOUR_PASSWORD -keypass YOUR_PASSWORD -dname "CN=Saolei, OU=Dev, O=wpiaopiao, C=CN"
# then create keystore/keystore.properties:
#   storeFile=keystore/release.keystore
#   storePassword=YOUR_PASSWORD
#   keyAlias=saolei
#   keyPassword=YOUR_PASSWORD
./gradlew :app:assembleRelease   # produces the signed app-release.apk
```

`android/keystore/` is gitignored and must never be committed. **Back up the keystore and its passwords
outside the repository** (e.g. `~/saolei-secrets-backup/` with `chmod 700`/`600`) — without them you
cannot ship further releases under the same key. Do not put backups in `dist/` or any other directory
inside the repository; a single "zip the whole folder" mistake would leak the signing key and its
plaintext passwords. For CI, store the keystore's Base64 and the passwords as repository secrets.

**Before releasing.** Walk through the [release checklist](docs/release-checklist.md) (version
consistency, local build/tests, fork tag rehearsal, SHA-256 verification, unsigned-build notice and
rollback steps) so the first real release is a rehearsal, not a surprise.

## Known limitations

- **Desktop builds are unsigned / not notarized**: macOS Gatekeeper and Windows SmartScreen may warn on
  first launch. No auto-update is implemented; download new releases manually. A tray icon is provided,
  but some Linux desktops don't support tray icons (the app then silently runs without one).
- **No Web/PWA build** yet — the desktop app is Electron-only.
- **Platform coverage**: CI builds macOS arm64, Windows x64 and Linux x64 only; Intel macOS and
  Linux/Windows arm64 are not built.
- **Android is tuned for the KPA handheld**: it is locked to landscape and designed for 960×640; phone
  portrait, tablets and foldables are not adapted. `targetSdk` is 34 and no Android App Bundle / privacy
  policy / store listing is prepared, so Google Play publishing is not ready.
- **No-guess fallback**: on very large or high-density boards the solver may not find a solvable layout
  within the budget; the app warns in the HUD and the board may require guessing. This is reported
  explicitly rather than silently.
- **No network features**: no cloud sync, accounts, leaderboards or online replays — all data is local
  to the device. There is no per-device export/import in the UI yet.
- **Fixed app id** (`com.minesweeper.app` / `com.wpiaopiao.saolei`). Changing it would move the user-data
  directory and lose local statistics, so it is intentionally unchanged in 1.3.0.
- **i18n scope**: UI strings are available in Chinese and English; some developer-facing logs and
  internal comments remain in Chinese. Additional languages are not included.
- `dist/` is gitignored and may contain your local install artifacts; `npm run clean` removes build
  caches and `.DS_Store` files (add `--dist` to delete local installers too).

## License

[MIT](LICENSE) © 2026 w-PiaoPiao
