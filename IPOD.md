# iDevice viewer (fork of AVNC)

A fork of [AVNC](https://github.com/gujjwal00/avnc), GPL-3.0-or-later, used as the screen and buttons for the QEMU-iOS emulators on the phone: the devos50-based iPod touch 2G and samhenrigold's tree with every Light Touch board, iPod touch 1G–4G, iPhone 2G/3GS/4 and iPad. The emulator runs in Termux, and this app is its front end. It was called "iPod Touch" until it showed more than the iPod.

## What differs from AVNC
- **Launcher (`ipod/IPodActivity.kt`):**
  1. Checks whether the emulator's VNC server on `127.0.0.1:5900` is up.
  2. If not, starts the emulator through Termux's `RUN_COMMAND` intent, then waits (up to 2 minutes):
     - `device start --no-viewer` when a device copied from Light Touch is active (`~/devices/current`; see `qemu-ios-sam/INSTALL-ANDROID.md`)
     - `ipod start --no-viewer` otherwise (qemu-ios)

     The app runs a small `bash -c` dispatcher (`IPod.DISPATCH`), so it needs no setting for which emulator is installed. "Shut down device" goes the same way (`device stop` shuts iOS down cleanly).
  3. Opens the viewer.

  "Other VNC servers" opens AVNC's normal server list.
- **Button bar (`res/layout/ipod_buttons.xml`, `ipod/IPod.kt`):**
  - Vol −, Home, Power and Vol + at the bottom of the viewer.
  - Each button sends a key chord that both emulators map to that iPod button, as keysyms and QEMU scancodes: Home is Command+Shift+H, Power is Command+L+P, and volume is Command+- and Command+=. qemu-ios (`ipod` command) reads the plain letters, and qemu-ios-sam (Light Touch's) reads the Command combinations.
  - The key is held for as long as you touch the button, so a long press on Power works.
- **Accelerometer (`ipod/IPodAccelerometer.kt`, the `iosAccel` part of `cpp/native-vnc.cpp`):**
  - The phone's accelerometer becomes the iPod's: turning the phone turns the iPod, so iOS rotates its interface, and tilt-steered games steer.
  - It is read along the axes of the screen as shown. The framebuffer stays fixed to the screen, the way the real panel is fixed to the iPod, so a landscape app is upright once you hold the phone sideways. The viewer itself stays portrait.
  - Only qemu-ios-sam takes it. The app offers a private VNC pseudo-encoding (`0x69414363`), and the emulator acknowledges it when its machine has an accelerometer. Only after that does the app send QEMU client messages `255, 0x69, x, y, z` (big-endian int16, milli-g, iOS axes). Other servers ignore the encoding, so nothing is sent to them.
  - Readings go at most every 20 ms, and only when they change by 8 mg or more (otherwise once a second). The sensor is off while the viewer is in the background.
- **Toolbar drawer:** a "Shut down device" button runs `device stop` (or `ipod stop`, through the same dispatcher as the launcher).
- **Direct touch** (`gesture_direct_touch`, on by default; `TouchHandler.handleDirectTouch` and `Dispatcher.onDirectTouch*`):
  - One finger is the touchscreen: down presses, moving drags, and lifting releases, with no gesture detection in between. A tap lasts exactly as long as the finger is down; AVNC's instant click was too short for iOS.
  - A second finger ends the touch and pinches to zoom.
- **Defaults:**
  - portrait orientation
  - remote cursor hidden
  - auto-reconnect on
  - AVNC's virtual-keys bar hidden
  - raw encoding (`useRawEncoding` in `IPod.createProfile()`): the emulator runs on the same phone, so pixels go uncompressed. With Tight/JPEG, QEMU sent often-changing areas lossy, and the encoding cost CPU the emulator needs.
  - One-finger swipe drags and long press holds. Both only matter if direct touch is turned off.
- **Identity:** app name "iDevice" (it shows every board the emulators run, not only the iPod), application ID `io.github.qemuios.ipodviewer` (unchanged, so updates install over the old "iPod Touch" app), next to AVNC. Its launcher icon is a white lowercase "i" on AVNC's blue background (`drawable/ic_launcher_foreground.xml` and `_monochrome.xml`; the `mipmap-*` PNGs for Android before 8 are rendered from the same shapes).
- **Build:**
  - Native code is built for arm64-v8a only.
  - LeakCanary is removed, so debug builds add no "Leaks" launcher icon.

## Requirements in Termux
Run `qemu-ios-sam/scripts/android/termux-build.sh` (the `device` command) or `qemu-ios/scripts/android/setup.sh` (the `ipod` command). Both set `allow-external-apps = true` in `~/.termux/termux.properties`. On first start, the app asks for Termux's "Run commands in Termux environment" permission.

## Build
```
git submodule update --init
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
PATH="$PWD/tools/host-bin:$PATH" ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
The first build downloads the NDK and CMake and builds libjpeg-turbo and LibreSSL through vcpkg, which takes a while. vcpkg needs `pkg-config` on the build machine. `tools/host-bin/` holds a macOS x86_64 `pkgconf` built with the bundled vcpkg, for Macs without Homebrew. If the build runs while a Gradle daemon started without it on the PATH, run `./gradlew --stop` first.
