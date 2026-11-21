# MMC Patcher

A Flutter APK with a floating overlay injector for Mini Militia Classic. The app screen has one Launch button; every toggle lives in the floating panel over the game.

## Overlay toggles

- **God Mode** — one toggle for all four damage functions (humanoid/hawk/worm/soldier)
- **Infinite Ammo** — clip + reserve
- **Security Bypass** — hides H4X alerts, bypasses ballistics validation
- **Aimlock** — snaps fire angle to the nearest enemy
- **ESP Boxes / ESP Lines** — transparent full-screen overlay drawn from live memory

## Why aimlock was stuck, and what changed

Investigation against the RE dump found three causes:

1. The multiplayer player dictionary needed **two** pointer reads: `dict = *(*(base + 0x13bf260))`. Reading only one gave junk elements, so no target was ever found and the injected stub kept returning its initial `0.0f`.
2. Campaign enemies are not in that dictionary at all — they live in `*(*(base + 0x13be0a0))`, with per-type body indirection (`+0x200` humanoid, `+0x230` hawk, `+0x228` worm).
3. The stub had no "no target" fallback and the view sway fields were left non-zero.

Fixes in `MemoryPatcher.kt`:
- double-dereferenced both dictionaries
- walk the all-drones dictionary with vtable classification
- zero `view+0x298/+0x29c/+0x2a4` (`view = *(localController + 0x150)`) each tick
- when no target exists, write the live base angle `view+0x2a0` into the stub slot instead of leaving the last value

The stub itself is unchanged and verified correct: `adr x8,#12 / ldr s0,[x8] / ret / <float> / nops` over the 24-byte `Joypad::getFireAngle()` at `0x6888f0`.

## ESP

`EspOverlay.kt` adds a transparent, touch-through WindowManager view and redraws at ~30 fps from `collectTargets()` + `camera()`. Camera comes from the director scene graph: `director = *(base+0x13ec228)`, `scene = *(director+0xa0)`, children at `node+0xb8` → `arr+0x18` → `{count at +0, objects at +8}`, node position at `+0x2c`. World→screen: `sx = x + camX`, `sy = winHeight - (y + camY)`.

## Build

`.github/workflows/flutter.yml` builds and signs on every push to `main`, or manually:

```
gh workflow run flutter.yml --repo FilipinoCollabEmtech/mmc-external-patcher
```

Repository: https://github.com/FilipinoCollabEmtech/mmc-external-patcher
