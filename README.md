# ALYN-SA-MP-FIX

Fixed build of Alyn SA-MP Mobile v17.x source — crash fixes + GitHub-based server status + in-game data downloader.

## What was fixed

- **Removed signature check crash** ("No remake idiot!"): the original source refused to run when re-signed with a different keystore. The check in `SAMP.java` is now disabled.
- **BASS audio native crash hardening** (`AudioStream.cpp`): `BASS_StreamCreateURL` failure no longer calls `BASS_ChannelPlay` on a null handle; `BASS_ChannelStop`/`BASS_StreamFree` are now null-guarded.
- **Removed Firebase/Crashlytics**: `google-services.json` was never included in the released source; the plugins and dependencies are removed so the project builds.
- **Server status via GitHub**: the old `https://alynsampmobile.pro/update.json` (`app_status`) check now reads `app.json` (`status_server`) from this repo: `https://raw.githubusercontent.com/Kallzzxa/ALYN-SA-MP-FIX/main/app.json`. Set `status_server` to `false` to show the "server down" notice.
- **In-game data downloader**: new `GameDataManager` (`ro.alynsampmobile.launcher.utils`) downloads the single `files.zip` package (MediaFire, URL from `app.json` `data_url`) and extracts it to the game directory, version-pinned via `data_version` + `SAMP/.data_ready` marker + `SAMP/SAMP.ide` sentinel.

## Remote config (app.json)

```json
{
  "status_server": true,
  "data_url": "https://www.mediafire.com/file/rhkel93l3frrgtn/files.zip/file",
  "data_version": "1",
  "data_size": 0,
  "data_sentinel": "SAMP/SAMP.ide"
}
```

- `status_server`: `true` = up, `false` = down (shows maintenance notice).

## Building

- Android Studio, JDK 17, Android SDK 35, NDK **25.1.8937393**, CMake.
- ABIs: `armeabi-v7a` + `arm64-v8a`.
- Requires GTA: San Andreas Android **2.10** game data (or the `files.zip` data package downloaded in-game); the client only works with that exact build of `libGTASA.so`.
- Release builds need your own signing config (the original `key.jks` was never released).

## Notes

- Based on the publicly released 17.x-era source (March 2025 snapshot).
- `app/build.gradle` versionName is `17.0`; the fixed line continues from here.
