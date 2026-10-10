# BlockHost — Minecraft servers on your Android phone

Kotlin + Jetpack Compose. Hosts Vanilla, Paper, Spigot (BuildTools), Fabric, Forge, NeoForge and custom JARs as real
OS processes using an Android-compatible OpenJDK that the app downloads on first use.

> **Build status: NOT compiled or run by the author.** This code was written in a sandbox with no Android SDK, no Gradle
> and no network, so there is no APK, no "successful debug build" and no on-device testing. Expect to fix a few compile
> errors on first build. See "Status checklist" below for what that means feature by feature.

## Build
Requirements: Android Studio Ladybug+ (or JDK 17 + Android SDK 35 + Gradle 8.9), internet for dependencies.

1. Open this folder in Android Studio and let Gradle sync (it downloads Gradle 8.9 per `gradle/wrapper/gradle-wrapper.properties`).
   There is no `gradlew` script/jar in the ZIP; Android Studio doesn't need it. For CLI: `gradle wrapper --gradle-version 8.9` once.
2. Debug APK: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
3. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Device requirements
- 64-bit ARM phone (arm64-v8a), Android 8.0+ (API 26). x86_64 emulators also work for testing.
- 4 GB RAM or more recommended (2 GB phones: Low preset, small worlds). ~1 GB free storage per server + ~200 MB per Java version.
- Internet for first setup (Mojang/Paper/Fabric/Forge/NeoForge downloads, Termux package repo for the JDK).

## Why `targetSdk = 28`
Android 10+ blocks executing binaries from an app's data directory for apps targeting API 29+. BlockHost must run a
downloaded `java` binary, so (like Termux) it targets API 28. Consequences: sideload only (not Play-Store eligible), and
Android 14+ may show a "built for an older version" notice. This is a deliberate trade-off.

## How it works
- **Java runtime**: `JavaRuntime` fetches Termux's bionic-built `openjdk-17`/`openjdk-21` .deb packages (+ dependencies),
  verifies SHA-256 from the repo index, extracts them into `files/runtime/usr`, rewrites absolute symlinks, and launches
  `java` with `LD_LIBRARY_PATH`. You can alternatively import your own Android-compatible JDK archive in Settings.
- **Installers**: Vanilla (Mojang manifest, SHA-1), Paper (API, SHA-256), Fabric (meta server jar), Forge/NeoForge (official installer
  `--installServer`, then `@user_jvm_args.txt @…/unix_args.txt` launch), Spigot (BuildTools compiled on-device, experimental),
  custom JAR import (validated as a JAR).
- **Process control**: real `ProcessBuilder` child process; state (Starting/Running/Stopping/Stopped/Crashed/Sleeping) comes from the
  process itself plus the Minecraft Server List Ping on the server's own port. Bounded 2,000-line console buffer, "stop" via stdin, 45 s grace then kill.
- **Memory safety**: heap may not exceed 55 % of total RAM; start is refused if free RAM < 80 % of the request.
- **Idle sleep**: when ping reports 0 players for N minutes (default 15), the server is stopped gracefully.
  **Wake-on-join** (optional): BlockHost then listens on the same port itself, shows a "sleeping" MOTD, and starts the server when a player
  tries to log in (that first attempt is rejected with a "waking up, retry" message). Needs the foreground service alive; not available if Android kills the app.
- **Foreground service** with persistent notification, partial wake lock + Wi-Fi lock held only while something is running/sleeping.
- **Networking**: LAN addresses, local ping test, optional public-IP lookup (api.ipify.org, on tap), port-forwarding guide, optional
  reverse-SSH tunnel to *your own* VPS (key encrypted with Android Keystore, host key pinned on first use).
- **Backups**: zip to app storage with retention; optional copy to any Storage-Access-Framework folder (Drive/OneDrive apps, SD card).
  No account, no paid service, nothing uploaded unless you choose a folder.

## Install & test on a phone (suggested order)
1. Install APK → Settings → *Java runtime* → install Java 21 (and 17). Watch for errors.
2. Create server: Paper + latest version, Recommended memory, accept EULA. Start it; console should reach "Done".
3. Network tab → *Test server*; join from Minecraft on the same Wi-Fi with the LAN address.
4. Stop/Restart; then try idle sleep (set 1 minute in server Settings), join to test wake-on-join.
5. Settings → allow unrestricted battery; lock the app in recents; keep the phone charging for longer tests.
6. Try Fabric, then Forge/NeoForge (long installs), then Backups.

## Honest limitations
- Android may kill background apps, throttle CPU, or drop Wi-Fi when the screen is off, especially on Xiaomi/Huawei/Samsung/OnePlus.
  **24/7 hosting is not guaranteed.** Servers die with the app and are not auto-resumed.
- A phone cannot stop DDoS attacks. Real protection needs filtering infrastructure (protected proxy/tunnel or hosting network).
  A tunnel hides your home IP but is not DDoS protection by itself.
- Mobile carriers usually use CGNAT, so port forwarding often can't work; use a tunnel or the same Wi-Fi.
- BlockHost cannot reliably test port forwarding from inside your own network (NAT loopback).
- Minecraft ≤ 1.16 (needs Java 8) is not supported by the runtime; Forge/NeoForge are offered for 1.17.1+ only.
  NeoForge versions using the newest numbering scheme are not listed.
- Heavy modpacks need more RAM than most phones have.
- Spigot must be built locally (licensing); it is slow and needs `git` from the runtime repo.

## Troubleshooting
| Symptom | Fix |
|---|---|
| "Java N runtime is not installed" | Settings → Java runtime → Install. |
| Runtime install fails with HTTP/package errors | Check network; the Termux repo layout may have changed → import a JDK archive instead. |
| Exit code 126/127 on start | Runtime not executable on this device/ROM (SELinux, noexec). Reinstall runtime; try another phone. |
| Exit code 137 / "killed" | Android OOM-killed it. Lower memory, close apps, exempt from battery limits. |
| "Port in use" | Another server/app uses that port; change `server-port` in Properties. |
| Forge/NeoForge installer fails | Read the install log; try a different loader version; ensure ≥ 1 GB free RAM and 1 GB storage. |
| Players can't join from internet | CGNAT / no forwarding / firewall; use the tunnel or LAN. |

## Status checklist
Legend: ✅ written, **not yet compiled or device-tested** · 🔌 needs outside infrastructure · ⛔ not implemented

| Feature | Status |
|---|---|
| Gradle project, manifest, permissions, foreground service | ✅ written |
| Compose UI: dashboard, wizard, console, files, properties, network, backups, settings | ✅ written |
| Java runtime download/import | ✅ written (highest-risk part: Termux package layout, linker paths, exec on your ROM) |
| Vanilla / Paper / Fabric install + launch | ✅ written |
| Forge / NeoForge install + launch (`@unix_args.txt`) | ✅ written (long installer runs; untested) |
| Spigot via BuildTools | ✅ experimental, likely fragile |
| Custom JAR import | ✅ written |
| Start/stop/restart/console/state/crash hints | ✅ written |
| server.properties editor (≈60 keys + unknown keys) | ✅ written |
| Memory presets + unsafe-allocation blocking | ✅ written |
| Idle shutdown | ✅ written |
| Wake-on-join (phone-side listener) | ✅ written; only works while the app stays alive |
| Local backups, restore, external/cloud-folder copy via SAF | ✅ written |
| SSH reverse tunnel | 🔌 needs your own VPS; ✅ code written, untested, RSA/ECDSA keys recommended |
| Router port forwarding | 🔌 manual in router; guide only |
| DDoS protection | ⛔ impossible on-phone; 🔌 needs external proxy/host |
| Managed cloud hosting / official cloud APIs (Drive API etc.) | ⛔ not implemented (SAF folder only) |
| Hosted playit.gg/ngrok-style tunnels | ⛔ not implemented |
| Unit/instrumented tests | ⛔ none |
