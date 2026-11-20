# Zep Launcher

Standalone native Android HOME-launcher prototype for a car head unit.

## Build and install

From PowerShell in this folder:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

The app also registers as an Android HOME app. To launch the prototype directly:

```powershell
adb shell am start -n com.zephiiel.zeplauncher/.MainActivity
```

## Prototype scope

- Android 7.0 (API 24) minimum; landscape-first layout.
- Lightweight native Android Views, with no Compose or third-party runtime dependencies.
- Minimal home screen with a live clock, enlarged left-side navigation bar, a GPS speedometer, and right-side Notification, Calendar, and Media widgets.
- The icon-only app drawer fades in and starts with a launcher-settings shortcut; remaining icons launch installed apps.
- Long-press an app in the drawer to favorite it; favorites appear in the sidebar above the single most recently launched app.
- The app-drawer button toggles between the drawer and home screen with a fade transition. Drawer row and column counts are configurable in launcher settings.
- Favorite actions use a rounded, translucent popup.
- Widget cards use an approximately 85%-opaque fill, so the home background remains visible through them. Android 7 does not provide system backdrop blur.
- Notification Access is required to show active notifications and media sessions. The Notification widget rotates among active alerts every five seconds; tapping opens the selected notification and its close button dismisses it.
- Calendar requests read-only calendar access when the user taps its widget and displays the next event within 30 days. Tapping it opens the calendar app when one is available.
- Media reads the active media session and shows album art with previous, play/pause, and next controls. Control support depends on what the media app exposes.
- The speedometer requests precise location access when tapped and displays GPS ground speed in km/h while Home is visible.
- Launcher settings include a wallpaper picker. Selected images are persisted and downsampled to fit the display.
- The version label is v0.1. The update button is a placeholder until an update source and APK feed are configured.
