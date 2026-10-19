# Native banner layout

- Profile home: anchored adaptive banner at the bottom.
- TV portrait: banner at the bottom; only the list area reserves its height.
- TV landscape: no banner and no reserved inset. The app is portrait everywhere except full-screen video, so landscape only happens in full screen.
- Native ads (`NativeAdController`) are drawn in Java inside the channel list/EPG and the Home screen, outside the SDK banner. They are enabled from C# (`setNativeAdConfig`) once `SessionCount >= SessionShowNative` and ad unit IDs exist.
- Pro ownership: no banner and no reserved inset. There is no Remote Config override for disabling ads.
- Loading/Error screens keep the banner visible. Home, TV and Loading/Error share a content container below the SDK banner at the Activity root; bringing Loading/Error to front only changes order inside that container.
- Fullscreen playback, reward flow, consent, background and profile/Pro dialogs hide the banner.
- A loaded banner supplies its physical height; failed/unavailable ads reserve no space. Native view measurements are read on the Android UI thread.
- Native reports `ui.banner-layout` (`mode|widthDp`, mode 1 only, portrait only) when its layout changes; `AppFlow` also re-checks Unity-side state every 0.25 s and asks native only as a safety net. Failed banner loads retry with backoff; the controller retains ad-unit fallback.
- TV channel rows: 44dp minimum (previously 64dp), name 14sp, metadata 11sp. Categories: 36dp minimum. Favorite icons are borderless, outlined when off and yellow when on.

## Validation

`Native/StbHost/gradlew.bat -p Native/StbHost test copyToUnity --offline`: JVM tests pass and the release AAR is copied into Unity (last run 2026-10-20 after the native-ad/orientation port; not a device test).

The editor menu `StbEmu/Build And Run Android (Dev)` builds `Builds/StbEmu-dev.apk` and runs it (`AndroidDevBuild`). Device checks: portrait and full-screen landscape (with system auto-rotate on and off), last row reachable above banner, fullscreen hide/restore, failed load, and No Ads/Pro restore of full scroll height.
