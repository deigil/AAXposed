# AAXposed

[![Fork](https://img.shields.io/badge/Fork-deigil%2FAAXposed-blue?logo=github)](https://github.com/deigil/AAXposed)
![Xposed Module](https://img.shields.io/badge/Xposed-Module-blue)
![Android SDK min 31](https://img.shields.io/badge/Android%20SDK-%3E%3D%2031-brightgreen?logo=android)
![Android SDK target 36](https://img.shields.io/badge/Android%20SDK-target%2036-brightgreen?logo=android)
![License GPLv3](https://img.shields.io/badge/License-GPLv3-lightgrey)

My personal fork of **AADisplay**: an Xposed/LSPosed module that projects a virtual display from
the phone onto the car screen through Android Auto, so you can run apps that Android Auto does not
normally allow (launchers, browsers, video, and so on).

This fork is tuned for **my own setup** and my own list of apps:

| | |
|---|---|
| Phone | Google Pixel 9 |
| Android Auto | 17.9.x (updates via Play Store) |
| Car | Mercedes-Benz E53 AMG (2019, MBUX) |

It is a work in progress. Things that work for me may not work for you, and anything not listed
under "Status" below has not been verified in the car.

> [!NOTE]
> The original project and most of this fork's lineage are Chinese-language. The app UI now has an
> English translation (`values-en`). `CHANGELOG.md` entries and the files under `docs/` are still
> mostly in Chinese.

## Goals of this fork

- **Usable in-car navigation.** Home / Back / Recents should work from the car's own input
  (MBUX touchpad, touchscreen, steering wheel), without touching the phone.
- **Settings you can pick from, not type into.** Choose a launcher, voice assistant, DPI preset and
  so on from what is actually installed on the phone.
- **My own app list.** Make it easy to choose which apps get projected and which stay on the phone.
- **Stay current with Android Auto.** The hooks use DexKit to find Android Auto internals, so each
  Android Auto release needs re-checking.

## Features

- Cast any app to the car screen, sized to the car's real resolution (no hard-coded resolution table).
- Phone-side floating control window: draggable, resizable (long-press to reset), touch passes
  through outside the card.
- Task switcher inside the floating window to change the app in front on the car.
- Optional: hide Android Auto's own left navigation rail and reclaim that space for the projection.
- Portrait apps are forced to fill the landscape car screen instead of being letterboxed.
- While projecting: suppress eye-care mode (MIUI/HyperOS), fix Gboard's floating keyboard,
  optionally keep Waze / Google Maps off the car screen.
- Settings pickers (new in this fork): DPI presets, installed voice assistants, each with a
  "Custom…" fallback.

## Requirements

- Android 12 or newer (SDK 31+)
- Root + LSPosed (or a compatible Xposed framework)
- Android Auto (`com.google.android.projection.gearhead`)

## Install and configure

1. Build the APK (see [Building](#building)) or take one from this repository's Releases, and
   install it.
2. In LSPosed, enable the module for:
   - Android Auto
   - System Framework
3. Reboot. The module hooks `system_server`, so **every module update needs a reboot**.
4. Open AAXposed and configure:
   - **Main screen:** auto open, projection launcher app, delayed-destroy time.
   - **Advanced settings:** hide car UI controls, force app fullscreen, DPI, voice assistant,
     Waze / Google Maps toggles, keyboard location, shell hooks.

### About "Hide car UI controls"

This option (`HideAaUiControls`, **on** by default) removes Android Auto's left rail and moves
control to the phone's floating window. That means there are **no Home / Back / Recents buttons on
the car screen**. If you want to drive it from the car, turn this option off. The older behaviour
then rebuilds Home / Back / Recents buttons in the car sidebar. (That code path predates Android
Auto 17.8, so it may not work on current versions.)

> [!NOTE]
> Some cars draw their own chrome next to the projection (for example a logo or a reserved strip).
> That is done by the head unit, not by the phone, so this module cannot remove it.

## Status

| Area | State |
|---|---|
| Builds (`assembleDebug`) | Yes |
| Settings pickers (DPI, voice assistant) | Built, not yet run on device |
| English UI | Done |
| Mercedes E53 / MBUX touchpad behaviour | Known issue, under investigation |
| In-car Home / Back / Recents | Planned |
| App-selection pickers (apps to disable, etc.) | Planned |

Upstream testing was done on HyperOS 3 (Android 16), Android Auto 17.3, and the Desktop Head Unit.
See [CHANGELOG.md](CHANGELOG.md) and [docs/releases/](docs/releases).

## Known system limitations

These come from how Android works, not from module bugs:

- **Eye-care mode** is a global SurfaceFlinger color matrix applied to the main display and
  broadcast to all displays; there is no per-display exclusion, so the projection turns yellow.
  By default the module turns eye-care off during projection and restores it afterwards, so the
  phone screen also loses eye-care meanwhile.
- **Gboard floating keyboard:** Gboard picks its keyboard mode from persisted settings and the
  device type (still "phone" while projecting), so the landscape virtual display hits the
  "phone + landscape" keys and opens the keyboard floating. By default the module only corrects
  the value Gboard reads and never writes Gboard settings. The cost is that the phone's own
  landscape mode will not float while projecting.

## Building

Requires JDK 17+ (Android Studio's bundled JBR works) and the Android SDK.

```bash
./gradlew :aa-display:assembleDebug
./gradlew :aa-display:assembleRelease
```

On Windows without `JAVA_HOME` set:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :aa-display:assembleDebug
```

The debug APK is written to `aa-display/build/outputs/apk/debug/`.

## Architecture notes

How Android Auto's Coolwalk left rail ("FacetBar") is reclaimed, and how the resolution is
settled on reconnect, is described in [docs/COOLWALK_FACETBAR.md](docs/COOLWALK_FACETBAR.md)
(Chinese).

## Credits

This is a fork of a chain of forks. All credit to the original authors:

- Upstream: [Nitsuya/AADisplay](https://github.com/Nitsuya/AADisplay) →
  [Stashboy/AADisplay](https://github.com/Stashboy/AADisplay) →
  [erxm/AADisplay](https://github.com/erxm/AADisplay) → this repository.
- The Coolwalk facetbar and media-placeholder logic comes from the `AADisplay-Split-Lite` branch of
  [lagXkjy/AADisplay](https://github.com/lagXkjy/AADisplay/tree/AADisplay-Split-Lite),
  by **qiang**, cherry-picked by path with original authorship preserved. The head-unit touch
  takeover from that branch was deliberately not included in the parent fork; bringing in-car
  control back is one of the goals here.

## License

GPLv3, inherited from upstream. See [LICENSE](LICENSE).
