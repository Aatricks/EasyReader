# Task: Fix PDF Progress Saving/Restoration

## Task Type: Bug Fix
## Expert Role: Expert Android Kotlin Developer

## Todo List
- [x] Investigate current progress saving logic in `ReaderViewModel` <!-- id: 0 -->
- [x] Investigate progress storage in `LibraryRepository` and `LibraryItem` <!-- id: 1 -->
- [x] Analyze `PdfContentLoader` for page-to-index mapping or specific PDF handling <!-- id: 2 -->
- [x] Reproduce the issue (identify why long PDFs fail) <!-- id: 3 -->
- [x] Develop a fix plan <!-- id: 4 -->
- [x] Implement the fix <!-- id: 5 -->
    - [x] Update `PdfContentLoader.kt`: Relax text filtering and add tall placeholders.
    - [x] Update `ReaderViewModel.kt`: Suppress progress updates for placeholders.
    - [x] Update `ReaderScreen.kt`: Fix paged mode scroll offset and viewport variable order.
- [x] Verify with tests <!-- id: 6 -->

## Findings
- **Placeholder Clamping**: Small placeholders ("...") in `PdfLazyList` caused `LazyListState` to clamp saved offsets during restoration. Fixed by using taller placeholders (100 newlines).
- **Premature Saving**: Restoring a long PDF triggered user interaction before content loaded, saving a "clamped" placeholder position. Fixed by suppressing updates in `ReaderViewModel` if content starts with "Loading page".
- **Aggressive Filtering**: `PdfContentLoader` was filtering out paragraphs shorter than 20 chars, which could lose headers/footers in PDFs. Relaxed to 2 chars.
- **Paged Mode Drift**: Paged mode was passing percentages instead of indexes to `updateScrollPosition`. Fixed in `ReaderScreen.kt`.
