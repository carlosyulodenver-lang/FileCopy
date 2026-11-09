# Block Puzzle

An original Android block-placement puzzle inspired by the general block-puzzle genre. It is not affiliated with or a copy of Block Blast's source code, artwork, sounds, or branding.

## Included
- 10×10 board, three-piece tray, drag-to-place controls
- Row and column clears, score, best score, combo bonus, game-over and restart
- Original generated visual effects and simple synthesized background music
- Settings: music, sound effects, vibration
- Assist mode: adapts piece selection to the current board and prefers shapes that can fit. It can be toggled from the menu while playing.
- GitHub Actions workflow builds and uploads a debug APK

## Build with GitHub
1. Create a GitHub repository and upload/push the contents of this folder.
2. Open **Actions** and run **Build Block Puzzle APK**, or push to `main`.
3. Open the successful workflow run and download the `Block-Puzzle-debug-apk` artifact.
4. Unzip the artifact and install `app-debug.apk` on your Android phone. You may need to allow installation from your browser/file manager.

## Build locally
Requires JDK 17 and Android SDK 35:
```bash
gradle assembleDebug
```

## Notes
- Package name: `com.example.blockpuzzle`
- App label: `Block Puzzle`
- The assist option is intentionally an easier/adaptive mode, not a guarantee against every possible board state.
- The project uses no third-party libraries or network permissions.
