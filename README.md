# EasyReader

[![Android](https://img.shields.io/badge/Android-11%2B-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-blue.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-1.11.3-brightgreen.svg)](https://developer.android.com/jetpack/compose)

An Android reader for web novels, manga, manhwa, and local files (like PDFs and EPUBs). Built with Kotlin and Jetpack Compose.

<p align="center">
  <img src="docs/screenshots/ManwhaReader.jpg" alt="EasyReader Manwha reader" width="200" style="margin: 10px;" />
  <img src="docs/screenshots/NovelReader.jpg" alt="EasyReader Novel reader" width="200" style="margin: 10px;" />
  <img src="docs/screenshots/Explorer.jpg" alt="EasyReader Explorer" width="200" style="margin: 10px;" />
</p>

## Features

- **Built-in scraper**: Extracts text and chapters from almost any novel site.
- **Offline reading**: Pre-fetch and cache entire series locally.
- **Unified search**: Search across multiple sources (like MangaBat and NovelFire) at once.
- **Formats**: Supports web novels, manga, EPUB, PDF, and HTML.
- **Local AI**: Summarizes chapters on-device using a local LLM.
- **No cloud BS**: Everything is stored locally on your device.

## Getting Started

### Prerequisites
- JDK 17+
- Android Studio / Android SDK (compileSdk = 37)

### Run the App

1. Clone the repo:
   ```bash
   git clone https://github.com/Aatricks/EasyReader.git
   cd EasyReader
   ```

2. Run the default debug build:
   ```bash
   ./gradlew :app:installStandardDebug
   ```

## Build Variants

The app has two build flavors:
- **`standard` (default)**: Normal build. Doesn't need the local `llmedge` library. AI summarization is visible but will show as unavailable.
- **`ai`**: Enables local AI summarization and downloads the `llmedge` dependency from Maven Central automatically.
  To run:
  ```bash
  ./gradlew :app:installAiDebug
  ```

> [!NOTE]
> The app package name is still `io.aatricks.novelscraper` so existing installs don't break, but the source code namespace is `io.aatricks.easyreader`.

## Contributing

Open a PR if you want to add a source or fix a bug. 
