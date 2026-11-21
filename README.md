# MMC External Patcher

A Flutter APK that patches **Mini Militia Classic** (`com.appsomniacs.mmc`) memory from outside the game. It mirrors the logic used by Android injector tools: find the process, locate the library base in `/proc/<pid>/maps`, and write raw bytes to `/proc/<pid>/mem`.

## How it works

1. `findPid()` scans `/proc` for a `cmdline` matching `com.appsomniacs.mmc`.
2. `findModuleBase()` reads `/proc/<pid>/maps` and takes the first mapping line containing `libcocos2dcpp.so`.
3. Each patch writes bytes at `module_base + offset`:
   - direct `dart:io` write to `/proc/<pid>/mem`, or
   - fallback through `su -c "printf ... | dd of=/proc/<pid>/mem ..."` for rooted devices.

`lib/memory_patcher.dart` holds the engine; `lib/main.dart` is the UI. `MainActivity.kt` exposes a MethodChannel that launches MMC via `getLaunchIntentForPackage()`.

## Built-in patches

Offsets come from the function addresses in `lgl_project/jni/Main.cpp` (`D:\mm_work`), converted to ARM64 byte overwrites:

| Group | Offset | Bytes | Effect |
|---|---|---|---|
| God Mode | `0x67cffc` | `C0 03 5F D6` | Humanoid damage → `ret` |
| God Mode | `0x677130` | `C0 03 5F D6` | Hawk damage → `ret` |
| God Mode | `0x95e4c0` | `C0 03 5F D6` | Worm damage → `ret` |
| God Mode | `0x8e2864` | `C0 03 5F D6` | SoldierController damage → `ret` |
| Infinite Ammo | `0x947a20` | `00 A6 8E 52 C0 03 5F D6` | `getAmmo` → 30000 |
| Infinite Ammo | `0x947954` | `00 A6 8E 52 C0 03 5F D6` | `getClip` → 30000 |

The **Custom patch** form accepts any library, hex offset, and hex byte string, so more patches can be added without rebuilding.

## Access requirement

Writing another app's `/proc/<pid>/mem` needs either a rooted device (`su`) or a virtual container that runs MMC in a process this patcher owns. On a stock non-rooted install the write will fail and the app will report it in the status line.

## Build

The APK is built in the cloud by `.github/workflows/flutter.yml` (the same pattern as the SeeRuta builder workflow):

- on every push to `main`, or manually with `gh workflow run flutter.yml`
- Java 17 + Flutter stable, release build signed with the shared JKS
- signing secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
- outputs `app-debug.apk` and `app-release.apk` as artifacts and in a GitHub Release

## Usage

1. Install `MMC_External_Patcher_release.apk`.
2. Open it and tap **Launch Game**.
3. Wait for a match to load, then tap **Attach** (status shows PID and library base).
4. Toggle God Mode / Infinite Ammo, or enter a custom patch.

Repository: https://github.com/FilipinoCollabEmtech/mmc-external-patcher
