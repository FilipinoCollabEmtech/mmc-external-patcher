# MMC External Patcher — Context Backup

Created as a handoff so a new OpenCode chat can continue this work. Start a new chat in
`D:\mm_work\mmc_flutter_patcher`, tell it to read this file first, and it will have the full state.

---

## 1. Objective

Build an **external memory-editing APK** for Mini Militia Classic (`com.appsomniacs.mmc`)
that runs as a separate app with a floating overlay. The user abandoned in-APK online
unlock (blocked by original signing cert/auth) and pivoted to external memory patching.

Current problem being investigated (as of this backup):
**Aimlock works (snaps to enemies) but once enabled the player cannot move the aim freely
with the joypad — the aim gets stuck even when there is no enemy nearby.**

A subagent is investigating this right now:
- subagent session ID: `ses_e02cb9526ffeOUiIwygQiGceR0`
- task: find root cause of joystick freeze while aimlock is on, propose fix
- If that session is still alive, re-invoke it with its sessionID to get the result; otherwise
  re-run the investigation using the prompt in section 8.

---

## 2. Repo / build state

- GitHub repo: `https://github.com/FilipinoCollabEmtech/mmc-external-patcher`
- Local project: `D:\mm_work\mmc_flutter_patcher` (Flutter)
- `main` branch = **`37ad7af`** (the confirmed working build; force-pushed/reset here)
- Latest GitHub Release = **`MMC Patcher - STABLE (37ad7af)`**, tag
  `build-37ad7afb2ac70c2d8780853630012f3cf003cc46`, with the working APK attached.
- Working APK on disk:
  `C:\Users\Admin\Music\webterminal\uploads\MMC_External_Patcher_release.apk` (42.9 MB)
- Signed with shared JKS:
  - keystore `D:\mm_work\mm-signing.jks`, alias `mm`, store/key password `changeit`
  - signer DN `CN=Seeruta Mod, OU=Modding, O=Local, L=NA, ST=NA, C=US`
  - SHA-256 cert digest `39b1a4873de5f4f4ff5ba7205885aa08ca3e87267600f259e178729c4e2d1a75`

### Build workflow
`.github/workflows/flutter.yml` (copied from SeeRuta's builder workflow) builds on every push
to `main`, signs using GitHub secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`), publishes APKs to a GitHub Release.
Manual trigger: `gh workflow run flutter.yml --repo FilipinoCollabEmtech/mmc-external-patcher`

### CRITICAL — which builds are good/bad
- **GOOD:** `37ad7af` and everything before it (`af8fabe`, `2d08adf`, `5e857f5`, `febe80a`).
  Source check: `LOS/terrain/cave` references = **0** in `MemoryPatcher.kt`.
  `37ad7af` is the newest and most fixed no-LOS build. **Do not move main past it.**
- **BAD:** `95ca9f3`, `ffed027`, `faf889c`, `a2e8288`, `3e3b0c3`, `384af22` — these added
  LOS code caves / terrain checks / triggerbot and made things worse. Their Releases were
  deleted. Four test APKs from these remain in uploads as `MMC_Patcher_<sha>.apk`.
- Note: `febe80a` is OLDER than `37ad7af` (git log is newest-first). This caused a prior mix-up.

---

## 3. Architecture of the patcher

Flutter app with one Launch button. Native Kotlin (`android/app/src/main/kotlin/com/mmc/patcher/mmc_patcher/`):

- `MainActivity.kt` — MethodChannel `com.mmc.patcher/launcher`; `start` requests
  `SYSTEM_ALERT_WINDOW`, shows floating menu, launches MMC, `moveTaskToBack(true)`.
- `FloatingMenu.kt` — draggable "MMC" badge; tap expands a **scrollable** panel with a header
  and ✕ close button. Toggles: God Mode, Infinite Ammo, Security Bypass, Aimlock,
  ESP Boxes, ESP Lines, Live debug data.
- `MemoryPatcher.kt` — all /proc memory reads/writes, patch groups, target enumeration,
  camera, debug dump, aimlock.
- `EspOverlay.kt` — transparent, touch-through full-screen WindowManager view, ~30 fps redraw.
- `DebugOverlay.kt` — live text overlay of PID/base/director/dictionaries/positions/camera/targets.

### Patching mechanism
Mirrors the ChoRok/ALP injector source: scan `/proc` for `cmdline` containing
`com.appsomniacs.mmc` → PID; parse `/proc/<pid>/maps` for `libcocos2dcpp.so` → base;
write bytes to `/proc/<pid>/mem` at `base + offset` (direct `RandomAccessFile("rw")`,
fallback `su -c "printf ... | dd of=/proc/<pid>/mem ..."`). Needs root or a virtual container
owning the process.

### Patch groups (`PATCH_GROUPS` in MemoryPatcher.kt)
- God Mode: `0x67cffc`, `0x677130`, `0x95e4c0`, `0x8e2864` → `C0 03 5F D6` (ret)
  — four damage functions; one toggle because disabling one leaves other entity types alive.
- Infinite Ammo: `0x947954`, `0x947a20` → `00 A6 8E 52 C0 03 5F D6` (mov w0,#30000; ret)
- Security Bypass: `0x83aea8` (H4X alert), `0x85e0b8` (ballistics)

### Key RE offsets (from `D:\mm_work\RE_DUMP\native\`)
- local controller: `**(base + 0x13bec80)`
- multiplayer players dict: `*(*(base + 0x13bf260))` — **TWO derefs**, previously a bug
- campaign all-drones dict: `*(*(base + 0x13be0a0))`
- CCDictionary first element `dict+0x18`, CCDictElement object `elem+0x108`, next `elem+0x120`
- drone body indirection: humanoid `+0x200`, hawk `+0x230`, worm `+0x228`
- soldier body: `ctrl+0x1a8` → body, x/y doubles at `body+0x30/+0x38`
- director: `*(base+0x13ec228)`, running scene `director+0xa0`, winSize `director+0xd0/+0xd4`
- cocos children: `node+0xb8` → arr, `arr+0x18` → data, count `data+0`, objects `data+0x8`,
  node position `node+0x2c/+0x30`
- `Joypad::getFireAngle()` at `0x6888f0`, 24 bytes: normally returns `[joypad+0x180]`
- `SoldierView::getFiringAngle()` at `0x8fb0ac`: base `view+0x2a0`, sway `+0x298/+0x29c/+0x2a4`
- `view = *(localController + 0x150)`

### Current aimlock implementation (the thing that freezes)
`toggleAimlock(true)` overwrites the 24-byte `Joypad::getFireAngle()` with:
`adr x8,#12 / ldr s0,[x8] / ret / <float at +12> / nops`.
A background thread every 16 ms calls `aimAngle()` and writes the float to `base+0x6888f0+12`.
`aimAngle()` = nearest target angle, else `baseAngle()` = `view+0x2a0`.
`zeroSway()` zeroes `view+0x298/+0x29c/+0x2a4` each tick.
`toggleAimlock(false)` restores the original 24 bytes.

---

## 4. Reference sources (working in-game mod)

- `D:\mm_work\lgl_project\jni\Main.cpp` — the ORIGINAL working in-game mod. Key aimlock:
  - `joypad_fireAngle()` ~line 236: `if (g_aimlock && g_lockedDeg > 0) return g_lockedDeg;
    return old_joypad_fireAngle(self);` ← **the no-target fallback that our stub lacks**
  - `applyAim()` ~line 244: sets `g_lockedDeg`, then zeros view+0x2a4/+0x298/+0x29c
  - `local_update()` ~line 518-574, `stage_draw()` ~line 578-644
  - hooks at lines 1007-1027 (including `0x6888f0` joypad_fireAngle)
- `D:\mm_work\RE_DUMP\native\cocos2dcpp_disasm.txt` — 138 MB ARM64 disassembly.
  Search with Python line-by-line; never load whole file into memory.
- `D:\mm_work\RE_DUMP\native\cocos2dcpp_dynsyms.txt` — dynamic symbols.
- `D:\mm_work\RE_DUMP\README.md` — RE dump inventory.
- `D:\mm_work\aictrl.txt` — SoldierAIController::updateStep disassembly.
- `D:\mm_work\chorok_src\ChoRok Injector\main.lua` — the ALP injector source that was
  reverse-engineered for the overlay design (source of the `/proc/mem` patch logic).
- `D:\mm_work\paldo_main.lua` — the CODM injector `main.lua` that revealed the hex-patch method.

---

## 5. Earlier background: why external patching

- In-APK LGL mod menu was built into Mini Militia Classic (`FINAL38_signed.apk` in uploads,
  70.7 MB). It works for ESP/aim locally but online Quick Join/Ranked is blocked by app
  identity/auth: self-signed APK returns `RL:[EAU,NAW,NAE,NEU,SEA,SOA] SC:0 RC:200` and
  `Region Servers Not Found`. Original signing keystore unavailable → real online impossible.
- User pivoted to external memory editor. Choices: AceTheGame, KittyMemoryEx, VirtualApp etc.
  were researched; then user provided the ALP/ChoRok injector sources and asked to port their
  behavior to a Flutter app built via the `.yml` workflow pattern from
  `FilipinoCollabEmtech/SeeRuta` (`.github/workflows/flutter.yml`).

---

## 6. Gotchas / mistakes already made (don't repeat)

1. `febe80a` is OLDER than `37ad7af` — don't call it "the version after".
2. Forgetting `base +` before global offsets made localController/dictionaries null. Fixed in `37ad7af`.
3. The bad LOS builds (`95ca9f3`+) were auto-created by pushes; don't let `main` move past `37ad7af`.
4. The webterminal uploads folder keeps getting wiped — the APK is always re-downloadable from
   the `build-37ad7af...` Release.
5. Kotlin compile pitfalls: non-local `return` inside `forEachLine` is illegal; byte literals
   need `.toByte()`.
6. GitHub "Latest" was stuck on a bad release until the bad releases were deleted and the
   stable release recreated. It now correctly points to `MMC Patcher - STABLE (37ad7af)`.
7. No device is connected via adb right now; verification has been static + CI build only.

---

## 7. Current UI state (user's complaint)

Floating panel is scrollable with a ✕ close button in the header. Debug overlay shows live
data. User's latest report: aimlock snaps correctly, but while enabled the joystick can no
longer move the aim — even with no enemies. This is the active bug.

---

## 8. Prompt used for the running subagent (re-use if needed)

Investigate why aim direction gets stuck when aimlock is enabled, even with no enemy, so the
player cannot move aim freely. Hypotheses: the patched `getFireAngle()` stub always returns the
float slot and never calls the original getter, so manual joystick input is ignored; the
`view+0x2a0` fallback may be stale/cached rather than following the stick; `zeroSway()` may
erase the manual aim delta; the correct fix may be a single in-process detour on
`Joypad::getFireAngle()` (as in Main.cpp) or writing directly to `Joypad+0x180` if a global
pointer to the Joypad can be found. Deliver exact root cause, cited offsets/disassembly, and
the most reliable concrete fix (ARM64 encodings if a stub change is proposed). Research only.

---

## 9. User preferences

- Tightly scoped responses, short bullets, no unnecessary background.
- Wants working artifacts, not hedged explanations.
- Prefers subagents for investigations.
- Shared keystore must be used for every build; delete old version APKs from uploads when
  creating a new one.
