# Native banner layout

- Profile home: anchored adaptive banner at the bottom.
- TV portrait: banner at the bottom; only the list area reserves its height.
- TV landscape: player left, list right, banner at bottom right within the list width.
- No Ads / Pro / remote NoAds: no banner and no reserved inset.
- Fullscreen playback, connection/reward flow, consent, background and profile/Pro dialogs hide the banner.
- A loaded banner supplies its physical height; failed/unavailable ads reserve no space. Native view measurements are read on the Android UI thread.
- Rotation changes the adaptive width and recreates the banner. Failed loads retry after 30 seconds; the controller retains ad-unit fallback.
- TV channel rows: 44dp minimum (previously 64dp), name 14sp, metadata 11sp. Categories: 36dp minimum. Favorite icons are borderless, outlined when off and yellow when on.

## Validation

`Native/StbHost/gradlew.bat -p Native/StbHost test copyToUnity --offline`: 49 native tests passed; release AAR copied into Unity.

`StbEmu/Build Android (Dev)` builds `Builds/StbEmu-dev.apk` without installing it. Device checks: portrait/landscape, last row reachable above banner, fullscreen hide/restore, failed load, and No Ads/Pro restore of full scroll height.
