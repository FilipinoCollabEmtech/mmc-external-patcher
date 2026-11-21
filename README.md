# MMC Patcher

A Flutter APK with a floating overlay injector for Mini Militia Classic. The app screen has one Launch button; every toggle lives in the floating panel over the game.

## Overlay

- **Launch** — requests "Display over other apps" if needed, shows the MMC badge, launches MMC, backgrounds itself.
- **MMC badge** — draggable; tap to expand/collapse the panel.
- **Panel** — dark themed, grouped toggles:
  - **God Mode** — one toggle for all four damage functions
  - **Infinite Ammo** — clip + reserve
  - **Security Bypass** — hides H4X alerts, bypasses ballistics validation
  - **Aimlock** — snaps fire angle to the nearest enemy

## Why one God Mode toggle instead of four

The native mod hooks four separate damage functions — humanoid, hawk, worm, and soldier controller. Disabling only one leaves the other entity types able to deal damage, so the panel applies all four as a single toggle.

## Aimlock

Implemented without any in-game injection. From the RE dump:

- local controller: `**(libcocos2dcpp + 0x13bec80)`
- remote players: `CCDictionary` at `*(base + 0x13bf260)`, elements at `+0x18`, linked via `+0x120`, controller at element `+0x108`
- position: `controller + 0x1a8` → body, `body + 0x30/0x38` → x/y doubles
- `Joypad::getFireAngle()` (`0x6888f0`) returns `[joypad + 0x180]`

The patch overwrites `getFireAngle` with `ADR x8,#12 / LDR s0,[x8] / RET / <float> / NOPs`, and a background thread writes the computed angle (`atan2(-dy, dx)`, normalized to 0–360) into that slot every 16 ms. Toggling off restores the original 24 bytes.

Writing another app's memory needs root (`su`) or a virtual container that owns the MMC process.

## Build

`.github/workflows/flutter.yml` builds and signs on every push to `main`, or manually:

```
gh workflow run flutter.yml --repo FilipinoCollabEmtech/mmc-external-patcher
```

Repository: https://github.com/FilipinoCollabEmtech/mmc-external-patcher
