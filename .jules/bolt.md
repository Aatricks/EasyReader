## 2024-05-22 - SubcomposeAsyncImage Performance
**Learning:** `SubcomposeAsyncImage` uses subcomposition to support Composable `loading` and `error` parameters, which is slower than regular composition and not recommended for lazy lists.
**Action:** Use `AsyncImage` with `onState` callback or `listener` to update local state and overlay a `Box` for loading/error indicators, avoiding subcomposition overhead in critical UI paths like `LazyColumn`.
## 2024-05-22 - Manga Pipeline Blocking
**Learning:** In `ContentRepository.kt`, `parseHtmlDocument` blocks until `fetchImageDimensions` completes for all images in manga chapters. This delays content rendering significantly.
**Action:** Refactor `ContentRepository` to return image list immediately and fetch dimensions/merge split pages asynchronously in a post-processing step.
