# AADisplay

[![Fork](https://img.shields.io/badge/Fork-Nitsuya%2FAADisplay-blue?logo=github)](https://github.com/Nitsuya/AADisplay)
![Xposed Module](https://img.shields.io/badge/Xposed-Module-blue)
![Android SDK min 31](https://img.shields.io/badge/Android%20SDK-%3E%3D%2031-brightgreen?logo=android)
![Android SDK target 36](https://img.shields.io/badge/Android%20SDK-target%2036-brightgreen?logo=android)

Production-ready fork of [Nitsuya/AADisplay](https://github.com/Nitsuya/AADisplay), focused on Android Auto 16.x compatibility, TaskView stability, and reliable display sizing behavior.

## Version

- Current module version: `0.23#16.8-r1`
- See [CHANGELOG.md](CHANGELOG.md) and [RELEASE_NOTES_v0.23.4.md](RELEASE_NOTES_v0.23.4.md)

## Requirements

- Android 12+ (SDK 31+)
- Rooted device with LSPosed (or compatible Xposed framework)
- Android Auto (`com.google.android.projection.gearhead`)

## Quick Start

1. Build and install the APK.
2. Enable the module in LSPosed for:
   - Android Auto
   - System Framework
3. Reboot device.
4. Open AADisplay and configure:
    - Auto Open
    - Disable Waze on AA
    - Disable Google Maps on AA
    - Default Launch Package
    - Delay Destroy Time

## Build

```bash
./gradlew :aa-display:assembleDebug
./gradlew :aa-display:assembleRelease
```

## Remotes / Credits

```
origin      https://github.com/erxm/AADisplay.git       # this fork
upstream    https://github.com/Stashboy/AADisplay.git   # fork source
reference   https://github.com/lagXkjy/AADisplay.git    # reference implementation (push disabled)
```

The Coolwalk facetbar / media-placeholder chain is imported from the
`AADisplay-Split-Lite` branch of [lagXkjy/AADisplay](https://github.com/lagXkjy/AADisplay/tree/AADisplay-Split-Lite)
by **qiang** `<jqiang.hi@163.com>`. The import is a path-restricted cherry-pick onto
`vendor/coolwalk-import`, merged into `main` — every commit keeps its original author,
author date, and source SHA. To refresh it:

```bash
git fetch reference AADisplay-Split-Lite
git log --oneline main..reference/AADisplay-Split-Lite -- \
    aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/aa/coolwalk/
```

Not imported: the custom app selector, Bluetooth keyboard/mouse support, and the
lyrics/cover sync chain. `AaCoolwalkHuTouchHook` (head-unit touch takeover) is
intentionally absent — this fork keeps control on the phone-side floating window.

## License

Inherited from upstream. See [LICENSE](LICENSE).
