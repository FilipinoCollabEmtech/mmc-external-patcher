# MMC Patcher

A Flutter APK with a **floating overlay injector**, structured like the ALP/ChoRok injectors: the app itself has one Launch button; every toggle lives in the floating icon that appears over the game.

## Layout

- **App screen** — only a Launch button. It requests "Display over other apps" if needed, adds the floating icon, launches Mini Militia, and moves itself to the background.
- **Floating icon** — a draggable "MMC" badge. Tap it to expand/collapse the patch panel.
- **Patch panel** — switches for God Mode (4 damage functions) and Infinite Ammo/Clip, each calling the memory patch directly.

## Mechanism (from the ALP / ChoRok source)

`android/app/src/main/kotlin/.../MemoryPatcher.kt` mirrors the injector logic:

1. scan `/proc` for a `cmdline` matching `com.appsomniacs.mmc` → PID
2. read `/proc/<pid>/maps`, first line containing `libcocos2dcpp.so` → base
3. write bytes at `base + offset` into `/proc/<pid>/mem` (direct write, `su`+`dd` fallback)

Offsets come from `lgl_project/jni/Main.cpp`:
`0x67cffc`, `0x677130`, `0x95e4c0`, `0x8e2864` → `C0 03 5F D6` (`ret`, damage disabled);
`0x947a20`, `0x947954` → `00 A6 8E 52 C0 03 5F D6` (`mov w0,#30000; ret`, ammo refill).

Writing another app's memory needs root (`su`) or a virtual container that owns the MMC process.

## Build

`.github/workflows/flutter.yml` (same pattern as the SeeRuta builder workflow) builds and signs the release APK on every push to `main`, or manually:

```
gh workflow run flutter.yml --repo FilipinoCollabEmtech/mmc-external-patcher
```

Signing secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## Usage

1. Install the APK and open it.
2. Tap **Launch**; allow "Display over other apps" if prompted, then tap Launch again.
3. The MMC badge appears over the game. Tap it to open the toggle panel.
4. Drag the badge/panel to reposition; tap **MINIMIZE** to collapse back to the icon.

Repository: https://github.com/FilipinoCollabEmtech/mmc-external-patcher
