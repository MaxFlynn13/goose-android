# Contributing to Goose Android

## Development Model

This project uses **GitHub Actions for all compilation**. There is no local build toolchain required.

### Workflow

1. Edit code (Kotlin, scripts, frontend shims)
2. `git push` to your fork
3. GitHub Actions builds the APK (~10-15 min)
4. Download APK artifact from Actions tab
5. `adb install goose-android-arm64.apk` on test device

### What Gets Built in CI

| Job | What | Tool |
|-----|------|------|
| `build-goose-binary` | Cross-compiles the Goose Rust binary for ARM64 Android | `cross` + Android NDK |
| `build-frontend` | Builds the goose2 React frontend as static HTML/JS | `pnpm` + `vite` |
| `build-apk` | Assembles the Android APK with binary + frontend | `gradle` |

### Project Structure

```
goose-android/
├── .github/workflows/
│   └── build.yml              # CI pipeline (the build system)
├── app/
│   └── src/main/
│       ├── java/io/github/gooseandroid/
│       │   ├── MainActivity.kt      # WebView host
│       │   ├── GooseService.kt      # Foreground service (manages goose binary)
│       │   └── GooseBridge.kt       # JS↔Android bridge
│       ├── res/                      # Android resources
│       ├── assets/web/               # [Built in CI] Frontend bundle
│       ├── jniLibs/arm64-v8a/        # [Built in CI] goose binary
│       └── AndroidManifest.xml
├── frontend/
│   └── platform-shim.js             # Replaces Tauri APIs for WebView
├── scripts/
│   ├── patch-goose-build.sh         # Patches goose source for Android
│   └── patch-frontend.sh            # Patches frontend for WebView
├── build.gradle.kts                  # Root Gradle config
├── settings.gradle.kts
└── gradle/                           # Gradle wrapper
```

### Key Files to Edit

- **`GooseService.kt`** — How the goose binary is launched and managed
- **`GooseBridge.kt`** — Native features exposed to the web frontend
- **`platform-shim.js`** — Translates Tauri API calls to Android equivalents
- **`build.yml`** — The CI pipeline (build flags, patches, etc.)

### Testing

Test device: OnePlus 9 Pro (Android 14, ARM64, 12GB RAM)

```bash
# Install
adb install app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat -s GooseService GooseMain GooseBridge

# View WebView console
# Enable in chrome://inspect on connected machine
```

### Design Principles

1. **No Google Play Services** — Must work on degoogled devices
2. **No corporate/internal code** — Pure open-source Goose only
3. **No local toolchain required** — All builds via GitHub Actions
4. **Single APK** — Everything self-contained, no external dependencies
5. **Offline-capable UI** — Frontend is bundled, only LLM calls need internet
