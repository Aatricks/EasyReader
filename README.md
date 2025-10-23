# Novel Scraper - Android Kotlin Edition

A modern Android application for reading web novels, local HTML files, and PDF documents with a clean, distraction-free interface.

## 📱 Overview

Novel Scraper is a complete Kotlin rewrite of the original Java application, built with modern Android development practices. The app allows users to:

- **Read web novels** directly from URLs
- **Open local files** (HTML, PDF, EPUB)
- **Manage a library** of saved novels
- **Track reading progress** with automatic scroll position saving
- **Navigate chapters** using swipe gestures
- **Enjoy dark theme** optimized for reading

## ✨ Key Features

### Content Loading
- Web content scraping with JSoup
- PDF text extraction with iText7
- HTML file parsing
- EPUB support (basic)
- Intelligent content caching
- Image loading with Coil

### Reading Experience
- Edge-to-edge immersive display
- Smooth scrolling with lazy rendering
- Automatic scroll position restoration
- Chapter navigation via swipe gestures
- Dark theme optimized for reading
- Customizable text rendering

### Library Management
- Side drawer library interface
- Add novels by URL or file picker
- Track multiple novels simultaneously
- Reading progress indicators
- Batch operations (multi-select delete)
- Currently reading section

### Content Processing
- Smart page number removal (for PDFs)
- HTML entity decoding
- Text formatting and cleanup
- Chapter number extraction from URLs
- Title extraction from URLs

## 🏗️ Architecture

The app follows **Clean Architecture** principles with clear separation of concerns:

```
app/
├── data/
│   ├── local/              # SharedPreferences management
│   ├── model/              # Data models
│   └── repository/         # Repository pattern implementations
├── ui/
│   ├── components/         # Reusable UI components
│   ├── screens/            # Screen composables
│   ├── theme/              # Material3 theming
│   └── viewmodel/          # ViewModels for state management
└── util/                   # Utility functions

```

### Design Patterns

**MVVM (Model-View-ViewModel)**
- Clear separation between UI and business logic
- ViewModels manage UI state and handle user actions
- Repositories abstract data sources
- Models represent domain entities

**Repository Pattern**
- `ContentRepository`: Handles content loading and caching
- `LibraryRepository`: Manages library data persistence
- Abstracts data sources from ViewModels

**State Management**
- Kotlin StateFlow for reactive state updates
- Compose state hoisting for UI state
- Immutable data classes for state representation

## 🛠️ Tech Stack

### Core Android
- **Kotlin** - Modern, concise, and safe programming language
- **Jetpack Compose** - Declarative UI framework
- **Material3** - Latest Material Design components
- **Edge-to-Edge Display** - Modern immersive UI

### Jetpack Libraries
- **ViewModel** - Lifecycle-aware state management
- **Lifecycle** - Lifecycle-aware components
- **Activity Compose** - Integration between activities and Compose
- **Navigation Compose** - Type-safe navigation

### Asynchronous Programming
- **Kotlin Coroutines** - For asynchronous operations
- **Flow & StateFlow** - Reactive data streams

### Content Processing
- **JSoup 1.17.2** - HTML parsing and web scraping
- **iText7 Core 7.2.5** - PDF text extraction
- **OkHttp 4.12.0** - HTTP networking
- **Gson 2.10.1** - JSON serialization

### Image Loading
- **Coil 2.5.0** - Modern image loading for Compose

### Build & Dependencies
- **Gradle Kotlin DSL** - Type-safe build configuration
- **Version Catalogs** - Centralized dependency management

## 🎯 Key Improvements Over Java Version

### Modern Architecture
- ✅ MVVM architecture with clear separation of concerns
- ✅ Repository pattern for data access
- ✅ Kotlin Coroutines for async operations (vs. AsyncTask)
- ✅ StateFlow for reactive state management

### UI/UX Enhancements
- ✅ Jetpack Compose declarative UI (vs. XML layouts)
- ✅ Material3 design language
- ✅ Edge-to-edge display
- ✅ Smooth animations and transitions
- ✅ Better drawer implementation

### Code Quality
- ✅ Kotlin null-safety
- ✅ Extension functions for cleaner code
- ✅ Data classes for models
- ✅ Sealed classes for type-safe state
- ✅ Comprehensive documentation

### Performance
- ✅ LazyColumn for efficient list rendering
- ✅ Coil for optimized image loading
- ✅ Proper lifecycle management
- ✅ Better memory management

### Features
- ✅ Enhanced content caching
- ✅ Better error handling
- ✅ Reading progress tracking
- ✅ Multi-select operations
- ✅ Deep link support

## 🚀 Building and Running

### Prerequisites
- **Android Studio** Hedgehog (2023.1.1) or later
- **JDK 11** or later
- **Android SDK** with API level 30+
- **Gradle 8.0** or later

### Build Instructions

1. **Clone the repository**
   ```bash
   git clone https://github.com/Aatricks/Novel-Scraper.git
   cd Novel-Scraper/NovelScraper
   ```

2. **Open in Android Studio**
   - Open Android Studio
   - Select "Open an Existing Project"
   - Navigate to the `NovelScraper` folder
   - Wait for Gradle sync to complete

3. **Build the project**
   ```bash
   ./gradlew build
   ```

4. **Run on device/emulator**
   - Connect an Android device or start an emulator
   - Click "Run" in Android Studio or:
   ```bash
   ./gradlew installDebug
   ```

### Configuration

**Minimum Requirements**
- Minimum SDK: API 30 (Android 11)
- Target SDK: API 36 (Android 14+)
- Compile SDK: API 36

**Permissions**
The app requires:
- `INTERNET` - For downloading web content
- `ACCESS_NETWORK_STATE` - For checking connectivity
- `READ_EXTERNAL_STORAGE` - For Android 9 and below
- `READ_MEDIA_IMAGES` - For Android 13+

## 📂 Project Structure

```
NovelScraper/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/io/aatricks/novelscraper/
│   │   │   │   ├── MainActivity.kt                      # Main entry point
│   │   │   │   ├── data/
│   │   │   │   │   ├── local/
│   │   │   │   │   │   └── PreferencesManager.kt       # SharedPreferences wrapper
│   │   │   │   │   ├── model/
│   │   │   │   │   │   ├── ChapterContent.kt           # Chapter data model
│   │   │   │   │   │   ├── ContentElement.kt           # Content element (Text/Image)
│   │   │   │   │   │   ├── ContentType.kt              # Content type enum
│   │   │   │   │   │   ├── LibraryItem.kt              # Library item model
│   │   │   │   │   │   └── ReadingState.kt             # Reading state model
│   │   │   │   │   └── repository/
│   │   │   │   │       ├── ContentRepository.kt        # Content loading & caching
│   │   │   │   │       └── LibraryRepository.kt        # Library management
│   │   │   │   ├── ui/
│   │   │   │   │   ├── components/
│   │   │   │   │   │   ├── ContentRenderer.kt          # Content display component
│   │   │   │   │   │   └── LibraryItemCard.kt          # Library item card
│   │   │   │   │   ├── screens/
│   │   │   │   │   │   ├── LibraryDrawerContent.kt     # Drawer content
│   │   │   │   │   │   └── ReaderScreen.kt             # Main reading screen
│   │   │   │   │   ├── theme/
│   │   │   │   │   │   ├── Color.kt                    # Color palette
│   │   │   │   │   │   ├── Theme.kt                    # Theme configuration
│   │   │   │   │   │   └── Type.kt                     # Typography
│   │   │   │   │   └── viewmodel/
│   │   │   │   │       ├── LibraryViewModel.kt         # Library state management
│   │   │   │   │       └── ReaderViewModel.kt          # Reader state management
│   │   │   │   └── util/
│   │   │   │       ├── FileUtils.kt                    # File operations
│   │   │   │       └── TextUtils.kt                    # Text processing
│   │   │   ├── res/                                    # Resources (layouts, drawables, etc.)
│   │   │   └── AndroidManifest.xml                     # App manifest
│   │   ├── androidTest/                                # Instrumented tests
│   │   └── test/                                       # Unit tests
│   └── build.gradle.kts                                # App build configuration
├── gradle/
│   └── libs.versions.toml                              # Version catalog
├── build.gradle.kts                                     # Project build configuration
├── settings.gradle.kts                                  # Project settings
└── README.md                                            # This file
```

## 📦 Dependencies

### Core Dependencies
```kotlin
// Compose
androidx.compose.ui
androidx.compose.material3
androidx.compose.ui.tooling

// Lifecycle & ViewModel
androidx.lifecycle:lifecycle-viewmodel-compose
androidx.lifecycle:lifecycle-runtime-compose

// Coroutines
kotlinx-coroutines-android
kotlinx-coroutines-core
```

### Third-Party Libraries
```kotlin
// Web Scraping
org.jsoup:jsoup:1.17.2

// PDF Processing
com.itextpdf:itext7-core:7.2.5

// Networking
com.squareup.okhttp3:okhttp:4.12.0

// Image Loading
io.coil-kt:coil-compose:2.5.0

// JSON
com.google.code.gson:gson:2.10.1
```

## 🎨 UI Components

### ReaderScreen
Main screen with drawer layout for content display and library management.

**Features:**
- Lazy scrolling for efficient rendering
- Scroll position tracking
- Pull-to-refresh (gesture detection)
- Loading/error/empty states
- TopBar with title and menu

### LibraryDrawerContent
Side drawer for library management.

**Features:**
- URL input field for adding novels
- File picker button
- Currently reading section
- Library list with cards
- Multi-select mode
- Batch operations

### ContentRenderer
Renders text and images from content elements.

**Features:**
- Text formatting
- Image loading with placeholders
- Error state handling
- Clickable images for zoom

### LibraryItemCard
Displays library item with metadata.

**Features:**
- Title and progress display
- Reading status indicator
- Selection checkbox
- Progress bar
- Download indicator

## 🔧 Utility Functions

### TextUtils
- `removePageNumbers()` - Remove page numbers from PDF content
- `incrementChapterInUrl()` - Navigate to next chapter
- `decrementChapterInUrl()` - Navigate to previous chapter
- `extractTitleFromUrl()` - Extract title from URL
- `formatText()` - Format text for readability
- `cleanHtmlEntities()` - Decode HTML entities

### FileUtils
- `getFileName()` - Extract filename from URI
- `detectFileType()` - Detect file type (PDF, HTML, EPUB)
- `getInputStream()` - Get input stream from URI
- `formatFileSize()` - Format file size for display

## 🐛 Known Issues & Limitations

- EPUB support is basic (needs full implementation)
- Some websites may require JavaScript for content loading
- PDF extraction quality depends on source document
- Image loading requires internet connection for web images

## 🔮 Future Enhancements

- [ ] Text-to-speech integration
- [ ] Font customization
- [ ] Reading statistics
- [ ] Bookmark support
- [ ] Night light filter
- [ ] Auto-scroll mode
- [ ] Export/import library
- [ ] Cloud sync
- [ ] Enhanced EPUB support
- [ ] Reader settings panel

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## 👨‍💻 Author

**Aatricks**
- GitHub: [@Aatricks](https://github.com/Aatricks)

## 🙏 Acknowledgments

- **JSoup** - For excellent HTML parsing
- **iText** - For PDF text extraction
- **Coil** - For efficient image loading
- **Material Design** - For design inspiration
- **Android Jetpack** - For modern Android development tools

---

**Built with ❤️ using Kotlin and Jetpack Compose**
