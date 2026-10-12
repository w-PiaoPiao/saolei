# Minesweeper 🎯

[中文](README.md) | **English**

A classic Windows XP-style Minesweeper game — cross-platform desktop app for macOS · Windows · Linux.

For the handheld Android port (Konkr KPA), see [Android (Konkr KPA)](#android-konkr-kpa).

---

## Features

| Feature | Details |
|---|---|
| Three preset difficulties | Beginner 9×9/10, Intermediate 16×16/40, Expert 16×30/99 |
| Custom boards | Configurable rows, columns and mine count (up to 50×50) |
| Readable huge boards | Cell size has a readability floor; boards that no longer fit scroll instead of shrinking |
| Non-blocking no-guess generation | Runs on a background worker thread; the first click never freezes the UI and shows a "generating…" hint |
| Native menu bar | F2 starts a new game |
| Single instance | Relaunching focuses the existing window instead of opening a second one |
| Full keyboard control | All actions reachable by keyboard, with rebindable keys |
| Chording | Double-click, middle-click, or press left+right on a revealed number |
| Safe first click | 3×3 safe zone plus a guaranteed zero-cell flood reveal |
| Synthesized sound effects | 6 effects generated with the Web Audio API — no external files |
| Game statistics | Games played, win rate, streaks, best times |
| No-guess mode | Boards are always solvable by pure logic; the HUD warns when generation has to fall back |
| 3BV efficiency | Best 3BV/s per difficulty plus a chart of the last 20 games |
| Six themes | Preference persisted between runs |
| Feedback & animation | Shake animation on a failed chord, pressed-down highlight on neighbouring cells |
| Game protection & endgame feedback | Restart confirmation, red crosses on wrong flags, new-record banner |
| System theme following | Follows the system light/dark setting until a theme is picked manually |
| Unit tests | Built in with node:test — run `npm test` |

---

## Quick Start

```bash
npm install
npm start
```

## Tests

```bash
npm test
```

## Build

```bash
# macOS
npm run dist:mac

# Windows
npm run dist:win

# Linux
npm run dist:linux
```

## Download

Releases: [github.com/w-PiaoPiao/saolei/releases](https://github.com/w-PiaoPiao/saolei/releases)

| Platform | Format | Notes |
|---|---|---|
| macOS (Apple Silicon) | .dmg | Installer |
| Windows (x64) | .exe | Portable, double-click to run |
| Linux (arm64) | .AppImage | `chmod +x` then run |

## Tech Stack

Electron + Vanilla JavaScript + CSS3 + node:test (no framework dependencies)

---

## Android (Konkr KPA)

A native port for the 960×640 landscape Android handheld Konkr KPA (D-pad + ABXY). The code lives in `android/`.

- **Native Kotlin** — custom `View` (Canvas) rendering, immersive fullscreen, no browser layer.
- **Ported engine** — `android/core` is a pure-JVM module that ports the desktop `game.js` / `movement.js` / `keybindings.js` / `stats.js` method by method, covered by JUnit tests.
- **Gamepad controls** — D-pad to move (diagonals and hold-to-repeat supported), B reveals / chords, A flags (flag → question mark → clear), Start starts a new game, Select opens the menu.
- **Rebindable keys** — Menu → Key bindings; any key can be rebound (with an on-screen key test) and is persisted.
- **No-guess mode** — a menu toggle. Mines are regenerated until the solver proves the board is solvable by pure logic (single-point, subset and global rules; a conservative check). Generation runs on a **background thread** (the UI stays responsive and the HUD shows "generating a no-guess board…"). On very large boards (e.g. 50×50) or at high mine densities, if no solvable layout is found within the attempt and time budget, it degrades early — reusing the last layout and showing "⚠ no-guess protection unavailable" in the HUD instead of degrading silently.
- **3BV efficiency stats and chart** — the stats page shows the best 3BV/s per difficulty; Menu → Efficiency chart plots the last 20 games with an average line (left/right switches difficulty).
- **Mis-touch protection and endgame feedback** — restarting mid-game (Start / face button / switching difficulty) asks for confirmation first (defaulting to Cancel); wrong flags get a red cross on loss, and beating a record shows a "New record!" banner.
- **System light/dark following** — follows the system setting until a theme is picked from the menu (including while running).
- **Performance** — the board's static layer is cached in a reused bitmap and wired to the engine's dirty flags: an operation redraws only the cells that changed, and idle frames just blit the bitmap. Cursor movement and the timer tick invalidate only their own regions, and large flood reveals (thousands of cells on the first click) skip the per-cell animation past a threshold. A 50×50 board no longer redraws everything every frame or on every click.
- **Touch support** — **every dialog is touch-operable** (menu items tap to activate; the custom-board dialog taps a row to select, taps ◀▶ to adjust and has explicit OK/Cancel buttons; Stats/About close on tap; the chart switches difficulty via the arrows; key bindings enter recording when tapped). Difficulty buttons in the bottom bar are directly tappable (going through the same restart confirmation as the gamepad); pressing a revealed number previews the chord area; huge boards (e.g. 50×50) scroll in a viewport, following touch drags (re-anchored past the threshold, so the content no longer jumps) or gamepad cursor movement.
- **Readable huge boards** — cell edge floor of 20px, digits at 60% of the cell, bevel width scaling with the cell, so 50×50 no longer renders 8px glyphs.
- **Otherwise aligned with the desktop version** — three difficulties plus custom boards, 6 themes, 6 runtime-synthesized sound effects, statistics, pause/resume, safe first click, shake on a failed chord.

### Build and install

```bash
cd android
./gradlew :app:assembleDebug          # produces app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Troubleshooting: if your project path contains non-ASCII characters (e.g. a Chinese directory name) and Kotlin compilation fails or paths are escaped as `uXXXX`, copy `android/` to a plain ASCII path and build there (GitHub CI is unaffected).

On the KPA, enable "Developer options → USB debugging" first. Debug builds can be sideloaded directly; for release builds use `assembleRelease` with a signing config (R8 shrinking and obfuscation are enabled for release, bringing the APK down from roughly 2 MB to about 170 KB).

### Controls

| Button | Action |
|---|---|
| D-pad | Move the cursor (diagonals supported, hold to repeat) |
| B | Reveal a cell; on a revealed number it chords |
| A | Flag a cell (flag → question mark → clear) |
| Start | New game (asks for confirmation mid-game) |
| Select | Menu (difficulty / theme / key bindings / stats, …) |
| Back | Close a dialog / open the menu |

Touch (optional): tap to reveal or chord, long-press to flag, tap the face to start a new game, drag to browse huge boards. **Every dialog is touch-operable** — menu items tap to activate; in the custom-board dialog, tap a row to select it, tap ◀▶ to adjust the value and tap OK/Cancel to submit; Stats and About close on tap; the efficiency chart switches difficulty via its arrows; key bindings enter recording when a row is tapped. Tapping outside a dialog acts like Back.

---

## CI / Automated releases

- **Desktop** — pushing a `desktop-v*` tag (e.g. `desktop-v1.3.0`) triggers [GitHub Actions](.github/workflows/build.yml), which runs the unit tests, then builds macOS / Windows / Linux packages and publishes them to GitHub Releases automatically.
- **Android** — pushing a `v*` tag (e.g. `v1.2.1`) triggers [android.yml](.github/workflows/android.yml), which runs the unit tests and builds a debug APK. If the repository has the signing secrets configured (`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`), it also builds a **signed release** and publishes it to GitHub Releases automatically. The release version is injected from the tag (`v1.2.3` → `versionName=1.2.3`, `versionCode=10203`), and the in-game About page shows the runtime version instead of a hard-coded one.

### Signing the Android release

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

The `keystore/` directory is gitignored, so keys and passwords never enter the repository; **back up the keystore and its passwords yourself** (without them you cannot ship further releases under the same key). For CI releases, store the keystore's Base64 and the passwords as repository secrets and the whole release runs automatically.
