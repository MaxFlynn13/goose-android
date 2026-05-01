# Goose Android

A standalone Android application that runs the [Goose AI agent](https://github.com/block/goose) natively on Android phones and ChromeOS devices.

## What is this?

This project packages the open-source Goose AI agent into a single Android APK that:
- Runs the `goose` Rust backend as a local service on your device
- Serves the Goose web UI in an embedded WebView
- Communicates over WebSocket on localhost (no cloud relay needed)
- Works on Android 14+ phones with 6GB+ RAM
- Works on ChromeOS devices via Android app support (no Crostini/Linux required)

## Architecture

```
┌─────────────────────────────────────────┐
│            Android APK                  │
├─────────────────────────────────────────┤
│  WebView (React frontend)              │
│       ↕ WebSocket ws://127.0.0.1:PORT  │
│  ForegroundService (goose serve)       │
│       ↕ HTTP to cloud LLM APIs        │
│  Cloud (Anthropic / OpenAI / etc.)     │
└─────────────────────────────────────────┘
```

## Requirements

- Android 14+ (API 34+)
- ARM64 device (aarch64)
- 6GB+ RAM recommended
- Internet connection (for cloud LLM APIs)
- An API key for your preferred LLM provider

## Building

All builds happen via GitHub Actions. No local toolchain required.

1. Push to `main` or create a tag
2. GitHub Actions cross-compiles the Goose binary for Android
3. GitHub Actions builds the React frontend
4. GitHub Actions assembles the APK
5. Download the APK from the Actions artifacts tab

## Installing

```bash
# Download goose-android-arm64.apk from GitHub Actions artifacts
adb install goose-android-arm64.apk
```

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup.

## License

This project builds upon [Goose](https://github.com/block/goose) which is licensed under Apache-2.0.

This wrapper/packaging is also licensed under Apache-2.0.
