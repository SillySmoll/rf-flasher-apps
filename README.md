# RadioFoundry Flasher apps (unofficial)

Windows and Android apps for the [RadioFoundry](https://github.com/radiofoundry/radiofoundry) web installer
(radiofoundry.org/flash), built from its MIT-licensed source. These apps aren't made or supported by the RadioFoundry
project. Both bundle the site built from upstream commit `SITE_VERSION`. Firmware images still come from RadioFoundry's
build service (`api.radiofoundry.org`), the same as on the website, so the apps need internet to install or update.

| | How it works |
|---|---|
| `windows/` | Electron. Serves the bundled site on `127.0.0.1:48731`. It adds a device picker for Web Serial (cables) and WebUSB (DFU bootloader), and sends build-service requests with the site's origin. |
| `android/` | WebView plus `assets/rf-android.js`. WebView has no WebUSB, so the script adds `navigator.usb` on top of Android's USB host API (`UsbBridge.java`). The page's own CDC-ACM/FTDI serial driver and DFU code then run unchanged, as they do in Chrome on Android. Build-service calls and file saves go through the app. |

## Build

- **CI:** push this folder to a GitHub repo. *Actions → Build apps* uploads `app-debug.apk` and the Windows zip.
  *Run workflow → update_site* rebuilds the site from upstream `main` first.
- **Android Studio:** open `android/` and click Run, or run `cd android && ./gradlew assembleDebug`.
  The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
- **Windows zip:** `tools/package-windows.sh` writes `dist/RadioFoundryFlasher-win-x64.zip`.
- **Newer upstream:** `tools/update-site.sh [ref]` rebuilds the site and copies it into both apps.

## Drivers and cables

- **Windows:** the programming cable uses its usual COM driver. The bootloader (0483:DF11) needs WinUSB through Zadig;
  see the bundled docs.
- **Android:** use a USB OTG adapter. The serial bridge supports CDC-ACM and FTDI cables, the same as Chrome on
  Android. CH340/CP210x cables aren't supported by the upstream page on Android either.

Licenses: RadioFoundry is MIT (`LICENSE.RadioFoundry.txt`). Electron is MIT. The wrapper code here is MIT too.
