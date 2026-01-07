# EasyReader

[![Android](https://img.shields.io/badge/Android-11%2B-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-blue.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-1.7.6-brightgreen.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**A lightweight, all-in-one Android reader for web novels, manga, manhwa, and local documents** — built with modern Kotlin and Jetpack Compose for a seamless, distraction-free reading experience.

<img src="ManwhaReader.jpg" alt="EasyReader Manwha reader screenshot" width="200" /> <img src="NovelReader.jpg" alt="EasyReader Novel reader screenshot" width="200" /> <img src="Explorer.jpg" alt="EasyReader Explorer screenshot" width="200" />

---

## Overview

EasyReader is a clean, focused environment for consuming digital content. Whether you're catching up on the latest light novel chapters, reading high-resolution manga, or parsing local PDFs, EasyReader handles it all with intelligent caching, automatic progress tracking, and AI-powered insights.

**Why EasyReader?**
- **Discovery Hub**: Integrated exploration of popular sources like MangaBat and NovelFire.
- **Smart Scraping**: "Smart Scraper" engine that extracts content from almost any web source.
- **Offline-first**: Pre-fetch and cache entire series for uninterrupted reading anywhere.
- **Distraction-free**: Immersive Material3 design with edge-to-edge display and dark theme.
- **AI-powered**: On-device LLM integration for instant chapter summaries.

---

## Features

### **Content Discovery**
- **Unified Explore**: Browse popular titles or search across multiple novel and manga sources simultaneously.
- **Extensible Sources**: Native support for major platforms with a generic fallback for unknown sites.
- **Detailed Previews**: View summaries, author info, and chapter lists before adding to your library.

### **Multi-Format Reading**
- **Web Novels**: Clean, text-focused reading.
- **Manga & Manhwa**: High-performance image rendering.
- **Local Documents**: Support for PDF, EPUB, and HTML files with automatic formatting.
- **Deep Link Support**: Add content directly by pasting a URL into the app.

### **Reading Experience**
- **Immersive UI**: Full-screen, edge-to-edge display with Material3 dynamic colors.
- **Smart Navigation**: Fluid swipe gestures to navigate chapters and auto-restore scroll position.
- **Intelligent Formatting**: Automatic removal of page numbers, ads, and HTML noise.
- **AI Summaries**: Generate concise summaries of long chapters using on-device [`llmedge`](https://github.com/Aatricks/EasyReader).

### **Library & Management**
- **Cloud-free Caching**: Store everything locally for privacy and speed.
- **Progress Tracking**: Visual indicators and "Currently Reading" markers for every item.
- **Batch Operations**: Easily manage your collection with multi-select delete and bulk updates.

---

## Installation

### Prerequisites
- **JDK 17+**
- **Android SDK**

### Quick Start

**1. Clone the repository**
```bash
git clone https://github.com/Aatricks/EasyReader.git
cd EasyReader
```

**2. Build and run**
- Open the project in Android Studio.
- Sync Gradle and click the **Run** button.
- Alternatively, use CLI:
```bash
./gradlew :app:installRelease
```

---

## Usage

### Discovering Content
1. Open the **Explore** tab from the side drawer.
2. Browse popular items or use the search bar to find specific titles across all supported sources.
3. Tap on an item to see details and click "Add to Library" or "Start Reading".

### Adding via URL
1. Copy any novel or manga chapter URL from your browser.
2. Open EasyReader's drawer and paste the URL.
3. The **Smart Scraper** will analyze the page and import the content automatically.

### AI Summaries
- While reading a chapter, tap the summary icon in the library header.
- The on-device LLM will analyze the text and provide a concise overview of the chapter's events.

---

## Support

If you find EasyReader useful, please consider:
- **Starring** the repository
- **Contributing** with new sources or feature enhancements
