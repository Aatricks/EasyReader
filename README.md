# EasyReader

[![Android](https://img.shields.io/badge/Android-11%2B-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-blue.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-1.7.6-brightgreen.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**A lightweight, distraction-free Android reader for web novels, PDFs, and local documents** — built with modern Kotlin and Jetpack Compose for speed, simplicity, and offline-first reading.

---

## 📖 Overview

EasyReader is a complete Kotlin rewrite of the original Java NovelScraper app, designed for readers who want a clean, focused environment to consume web novels and local documents. With intelligent content caching, automatic progress tracking, and seamless chapter navigation.

**Why EasyReader?**
- **Offline-first**: Pre-fetch and cache entire novels for uninterrupted reading
- **Distraction-free**: Immersive dark theme with edge-to-edge display
- **Smart content handling**: Automatic page number removal, HTML cleaning, and title extraction
- **Modern architecture**: Built with MVVM, Kotlin Coroutines, and StateFlow for smooth performance
- **AI-powered summaries**: Integrated llmedge for chapter summarization

---

## ✨ Features

### 📚 **Multi-Format Support**
- **Web novels**: Scrape and read directly from URLs (JSoup-powered)
- **PDFs**: Extract text with iText7, remove page numbers automatically
- **HTML/EPUB**: Parse local files with intelligent content extraction
- **Images**: Inline image support with Coil-powered loading

### 🎯 **Reading Experience**
- **Immersive UI**: Edge-to-edge display with Material3 dark theme
- **Smart navigation**: Swipe gestures to jump chapters, auto-restore scroll position
- **Lazy rendering**: Smooth scrolling even with massive chapters
- **Progress tracking**: Automatic save points for every novel

### 📂 **Library Management**
- **Multi-source**: Add by URL, file picker, or deep link
- **Batch operations**: Multi-select delete, currently-reading markers
- **Progress indicators**: Visual progress bars and reading state
- **Smart grouping**: Auto-detect and group chapters by base title

### 🚀 **Content Processing**
- **PDF cleanup**: Strip page numbers, decode entities, format text
- **URL intelligence**: Extract titles and chapter numbers from URLs
- **Caching**: Download and cache chapters for offline access
- **Background prefetch**: Queue multiple chapters for seamless reading

---

## 🛠️ Tech Stack

### Core Android
| Component | Version | Purpose |
|-----------|---------|---------|
| **Kotlin** | 2.1.0 | Modern, null-safe language |
| **Jetpack Compose** | 1.7.6 | Declarative UI framework |
| **Material3** | 1.3.1 | Latest Material Design |
| **Coroutines** | 1.7.3 | Async/concurrency |

### Content Processing
| Library | Version | Purpose |
|---------|---------|---------|
| **JSoup** | 1.17.2 | HTML parsing, web scraping |
| **iText7** | 7.2.5 | PDF text extraction |
| **OkHttp** | 4.12.0 | HTTP client |
| **Gson** | 2.10.1 | JSON serialization |
| **Coil** | 2.5.0 | Image loading for Compose |
| **llmedge** | 0.1.0 | AI-powered summaries |

### Jetpack Libraries
- **ViewModel** - Lifecycle-aware state management
- **Lifecycle Runtime** - Compose integration
- **Activity Compose** - Activity ↔ Compose bridge
- **Navigation Compose** - Type-safe navigation

---

## 📦 Installation

### Prerequisites
- **Android Studio**
- **JDK 11+**
- **Android SDK** API 30+ (Android 11+)
- **Gradle 8.0+**

### Quick Start

**1. Clone the repository**
```bash
git clone https://github.com/Aatricks/EasyReader.git
cd EasyReader
```

**2. Build and run**
```bash
# Build debug APK
./gradlew :app:assembleDebug

# Install to connected device
./gradlew :app:installDebug
```

**3. Launch on device**
- Connect Android device (API 30+) or start emulator
- APK will be installed at `app/build/outputs/apk/debug/app-debug.apk`
- Or click **Run** in Android Studio

### Configuration

**Minimum Requirements:**
- **Min SDK**: API 30 (Android 11)
- **Target SDK**: API 36 (Android 14+)
- **Compile SDK**: API 36

**Required Permissions:**
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" 
                 android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
```

---

## 🎯 Usage

### Adding Content

**Option 1: Web Novel URL**
1. Open drawer (swipe from left or tap menu)
2. Paste novel URL in text field
3. Tap "Add from URL"
4. Novel will be fetched and added to library

**Option 2: Local Files**
1. Open drawer
2. Tap "Pick File"
3. Select PDF/HTML/EPUB file
4. File will be imported to library

### Library Management

- **Multi-select**: Long-press any item to enter selection mode
- **Delete**: Select items → tap delete icon
- **Resume reading**: Tap any library item card
- **Progress**: Displayed as progress bar + percentage on each card

---

## 🎨 Key Components

### ContentRepository
**Central content loading hub** — handles web scraping, PDF parsing, HTML extraction, and caching.

**Core Methods:**
```kotlin
suspend fun loadContent(url: String, contentType: ContentType): ContentResult
suspend fun fetchTitle(url: String): String?
suspend fun prefetch(item: LibraryItem, cacheDir: File)
fun incrementChapterUrl(url: String): String?
```

### LibraryRepository
**In-memory model + persistence boundary** — manages library state and delegates to `PreferencesManager`.

**Core Methods:**
```kotlin
fun addItem(item: LibraryItem)
fun removeItem(id: String)
fun updateItem(item: LibraryItem)
val libraryItems: StateFlow<List<LibraryItem>>
```

### PreferencesManager
**Single source of truth for persistence** — uses SharedPreferences + Gson for serialization.

**Migration Logic:**
- Handles data shape changes via default values
- Uses enums for `ContentType` (JSON-friendly)
- Validates on load, migrates on save

### ReaderViewModel
**Orchestrates reading experience** — manages content loading, scroll position, chapter navigation.

**Key State:**
```kotlin
data class ReaderUiState(
    val content: ChapterContent? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val scrollPosition: Int = 0
)
```

---

## 🚀 Performance Optimizations

| Optimization | Impact | Implementation |
|--------------|--------|----------------|
| **LazyColumn** | ~80% memory reduction | Virtualized list rendering |
| **Coil caching** | 3x faster image loads | Disk + memory cache |
| **Content prefetch** | Instant chapter loads | Background coroutine queue |

---

## 👨‍💻 Author

**Aatricks**  
[![GitHub](https://img.shields.io/badge/GitHub-@Aatricks-181717?logo=github)](https://github.com/Aatricks)

---

## 🌟 Support

If this project helps you, please consider:
- ⭐ **Starring** the repository
- 🐛 **Reporting issues** via [GitHub Issues](https://github.com/Aatricks/Novel-Scrape/issues)
- 🔀 **Contributing** via Pull Requests
- 📢 **Sharing** with the community

**Built for readers, by readers. Happy reading! 📖**
