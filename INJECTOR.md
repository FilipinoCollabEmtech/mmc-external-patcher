# Runtime injector (branch `feat/runtime-injector`)

Loads the existing LGL mod (`libMyLibName.so`) into the **unmodified** Mini
Militia Classic process (`com.appsomniacs.mmc`, ARM64) at runtime with
`dlopen()` under root/ptrace. No APK repacking, no re-signing, original game
signature preserved. LGL keeps full capability: LOS, clean
`old_joypad_fireAngle` fallback, per-frame hooks and ESP.

## Architecture

```
Flutter launcher (lib/main.dart)
  └─ MethodChannel ── MainActivity.kt ── FloatingMenu.kt (INJECTOR section)
                                             │
                     NativeInjector.kt ◄──────┘
                       │ 1. stage assets → game's real lib dir (su + chcon)
                       │ 2. revert conflicting local patches
                       │ 3. su -c injector_cli <pid> <soPath>
                       ▼
        injector_cli (root) ── injector_core.cpp
          PTRACE_ATTACH → GETREGS → remote mmap(RWX)
          → write AArch64 payload + path + literals
          → SETREGS → CONT → wait for brk #0 trap
          → read dlopen handle → restore regs → DETACH
                       │
                       ▼
        target process: dlopen("/…/libMyLibName.so", RTLD_NOW)
          → lib_main() constructor → detached hack_thread()
          → waits for libcocos2dcpp.so → installs 22 HOOK()s
```

`LglControl.kt` writes LGL's native feature flags directly through
`/proc/<pid>/mem`. `JNI_OnLoad()` only registers the Java menu against
`com/android/support/Main` (`Setup.cpp:41-67`); a plain `dlopen()` never
calls it, so the in-game menu is unavailable by design and our overlay drives
the flags instead.

### Files

| Path | Role |
|---|---|
| `android/app/src/main/cpp/injector_core.cpp` | ptrace + AArch64 payload engine |
| `android/app/src/main/cpp/injector.cpp` | JNI entry point (root installs/self-tests) |
| `android/app/src/main/cpp/injector_cli.cpp` | `su`-run CLI (the normal path) |
| `android/app/src/main/cpp/marker.c` | PoC marker `.so` source |
| `android/app/src/main/cpp/CMakeLists.txt` | builds all four; copies CLI into assets |
| `android/app/src/main/kotlin/.../NativeInjector.kt` | staging, CLI/JNI dispatch, status |
| `android/app/src/main/kotlin/.../LglControl.kt` | native flag read/write |
| `android/app/src/main/assets/libMyLibName.so` | shipped mod (refresh when LGL is rebuilt) |
| `android/app/src/main/assets/injector_cli` | generated at build time, do not hand-edit |
| `lib/arm64-v8a/libmarker.so` | built by CMake, read from `nativeLibraryDir` at runtime |

## Build requirements

- Android Gradle Plugin externalNativeBuild + CMake 3.22.1 (installed by the
  Android SDK "CMake" component in CI).
- NDK as pinned by `flutter.ndkVersion` (`build.gradle.kts`).
- `abiFilters = arm64-v8a` only.
- `flutter build apk --release` produces the signed APK. No local NDK build is
  needed; CI is authoritative.
- **CI caveat:** this branch introduces native compilation for the first time.
  The runner must have the exact NDK revision pinned by `flutter.ndkVersion`
  and CMake 3.22.1 installed (and SDK licenses accepted), otherwise Gradle
  will fail before the APK is produced. Install via
  `sdkmanager "ndk;<flutter.ndkVersion>" "cmake;3.22.1"` if the image lacks them.

`injector_cli` is a PIE executable copied from CMake's output into
`src/main/assets` POST_BUILD, so **asset merging depends on the native build**
(the Gradle rule at the bottom of `app/build.gradle.kts`). After install the
app copies it to `/data/local/tmp/mmc_injector_cli`, `chmod 755`, and runs it
through `su -c`. The app process itself is unprivileged and cannot ptrace;
the CLI is the working path. The JNI wrapper is a fallback for root installs.

## Symbol findings (shipped 1,179,912-byte build, 2026-11-21)

`readelf`/`pyelftools`: ELF64 ET_DYN, EM_AARCH64, **stripped** — there is only
a `.dynsym` (2156 entries). `JNI_OnLoad` is exported at `0x9b71c`; the globals
and hack functions are **not** in any symbol table. They were recovered by
static analysis:

- `Changes()` at `0x8abb8` (`cmp w3,#0x11` for the 18 feature numbers), jump
  table of 16-bit offsets at `0x46b00`, base `0x8ac04`.
- `joypad_fireAngle()` at `0x82e18` reads
  `g_aimlock`/`g_smartTrigger`/`g_lockedDeg` back-to-back — independent
  cross-check of the three hot offsets.

Derived base-relative offsets (hardcoded in `LglControl.kt`):

| Global | Offset | Global | Offset |
|---|---|---|---|
| `g_espEnabled` | `0x12177c` | `g_aimlock` | `0x1216b0` |
| `g_espPlayers` | `0x121690` | `g_smartTrigger` | `0x1216b4` |
| `g_showDebug` | `0x1217b0` | `g_lockedDeg` (float) | `0x1216b8` |
| `g_triggerShoot` | `0x121784` | `g_godMode` | `0x121740` |
| `g_autoMelee` | `0x12179c` | `g_infAmmo` | `0x1216e0` |
| `g_oneShot` | `0x1216f0` | `g_rapidFire` | `0x12173c` |
| `g_noRecoil` | `0x1217bc` | `g_speedHack` | `0x121718` |
| `g_teamCheck` | `0x121694` | `g_enemyCheck` | `0x121698` |

`g_lockedDeg` is owned by the mod's own threads — the overlay never writes it
except the `0.f` release when the last aim feature is disabled (mirrors
`Changes()` cases 6/15).

### Re-deriving after an LGL rebuild

```bash
python -m pip install pyelftools capstone
# decode Changes() jump table (base of table 0x46b00, branch base 0x8ac04),
# then map case N -> strb target in the 0x121000 .bss page.
# Cross-check with joypad_fireAngle() around 0x82e18 reading 0x1216b0/b4/b8.
```

The exact helper scripts used are reproducible from the algorithm above; if
offsets drift, update `LglControl.kt` and this table together. The injector's
ptrace engine needs no address updates — it resolves `mmap`/`dlopen` from the
target's libc at runtime.

## Device test procedure (vivo V1818A, Android 8.1, ARM64, VirtualApp)

No device was connected during development. Run this in order:

1. Install the CI debug APK. Grant **Display over other apps**.
2. Launch MMC through the patcher's **Launch** button; wait for the game to
   reach the menu (LGL's `hack_thread` waits for `libcocos2dcpp.so`).
3. Open the overlay → **INJECTOR / LGL** → **PoC marker dlopen**.
   - Success: overlay toasts "PoC OK" and
     `/data/local/tmp/mmc_injector_marker.txt` contains
     `MMC_INJECTOR_DLOPEN_OK`.
   - If dlopen fails, read the logcat line `MMCInjector: dlopen handle=0x0`
     and check the staged path/SELinux context.
4. Only after the PoC passes, toggle **Load LGL mod**.
   - Success toast "LGL injected"; the status row shows
     `LGL ACTIVE · LGL LOADED pid=…` and `LGL FLAGS base=0x…`.
   - `adb shell su -c "grep libMyLibName /proc/$(pidof …)/maps"` shows the
     module mapped. LGL logs `LGL mod menu ready - ESP hooks installed`.
5. Toggle **Aimlock**, **ESP Boxes**, **God Mode**, **Infinite Ammo** in the
   overlay; confirm the target reacts in-game. Switch a feature off and
   confirm the effect stops.
6. Tap **Copy logs** and paste the output if anything fails.

## Conflict rule

Enabling LGL **reverts every local MemoryPatcher patch first**: all three
`PATCH_GROUPS` (god/ammo/security), the internal ESP hook at `base+0x9087d8`,
and the aimlock stub at `base+0x6888f0`. This is mandatory — LGL installs its
own hooks at those exact offsets. While `NativeInjector.active` is true:

- Combat/aim/ESP rows write LGL globals instead of game code.
- "Security Bypass" has no single LGL flag; LGL's `sendClientAlert` /
  `validateBallistics` hooks are always active after load.
- ESP boxes and lines share `g_espEnabled`; toggling one drives both.

Never enable both systems. Unloading LGL is not supported; restart MMC.

## Anti-tamper / Play Integrity caveats

- This requires root and ptrace into a third-party process. It will fail on
  stock devices and may trigger the game's anti-cheat or Android Play
  Integrity / SafetyNet attestation. Expect bans on online accounts.
- VirtualApp already hooks the target (SandHook); attaching with ptrace may
  interact badly with its own debugger hooks. Test the PoC first.
- The target keeps a 32 KB RWX mapping (the injection region). Anti-cheat
  scans for RWX pages can detect it.
- `dlopen` bypasses PLT interposition by calling libc's address directly, but
  LGL's own Dobby hooks still target `libcocos2dcpp.so` inside VA.
- Signature is untouched, but memory integrity checks are not.

## `FILL:` items — must be verified on device

- **`FILL: game process identity under VirtualApp`** — `MemoryPatcher.findPid()`
  matches the package string in `/proc/*/cmdline`. If VA names the process
  differently (e.g. `com.lody.virtual:...`), add a fallback that picks the
  process mapping `libcocos2dcpp.so`.
- **`FILL: ptrace permission`** — `su` must grant CAP_SYS_PTRACE. If
  `INJECT_RC=-2`, verify `su -c 'id'` shows uid=0 and that VA does not block
  ptrace.
- **`FILL: staged .so path / SELinux context`** — the injector derives the
  game lib dir from `/proc/<pid>/maps` and uses `chcon --reference=<libcocos>`.
  If `INJECT_RC=-9` (dlopen NULL), dump `dmesg`/logcat for avc denials and
  try `/data/local/tmp` with the appropriate context.
- **`FILL: linker namespace`** — Android 8.1's `dlopen` from the game's libc
  should accept a file in its own lib dir. If denied, the mod may need to be
  placed in the VA-visible lib path exactly.
- **`FILL: CLI execution`** — if the `injector_cli` asset is absent (CMake/AGP
  ordering), the injector falls back to JNI, which only works if the app itself
  is root. Verify the CLI is present in the installed APK under `assets/`.
- **`FILL: LGL constructor timing`** — `hack_thread` polls once per second for
  `libcocos2dcpp.so`; install the mod only after the game has loaded the lib.
- **`FILL: re-derive symbol offsets`** whenever `libMyLibName.so` is rebuilt —
  the offsets in `LglControl.kt` are tied to the 2026-11-21 build hash/size.

## Verification status

- `flutter analyze`: clean (Dart only; Kotlin/C++ are not compiled locally).
- No device connected — **nothing above was runtime-verified**. All offsets are
  statically derived from the shipped `.so`; all ptrace/dlopen behaviour is
  unverified on the target device.
