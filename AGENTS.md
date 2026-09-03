# AGENTS.md — EasyReader

Android reader app (web novels + manhwa). Kotlin, Hilt, Room, WorkManager, Compose, Coil 3.

## Build & verify (run before reporting done)

- `JAVA_HOME` is unset in non-interactive shells; export it first:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- Gate: `./gradlew testStandardDebugUnitTest detekt` — all tests green, detekt clean.
- Full build check when asked: `./gradlew assembleStandardDebug assembleAiDebug`
  (product flavors are `standard` and `ai`; there is no `full` flavor).
- Don't pipe gradle through `tail` and trust the exit code — verify `BUILD SUCCESSFUL`.

## detekt discipline

- Grandfathered violations live in `app/detekt-baseline.xml`. NEVER add `@Suppress`
  annotations, file-level or member-level — the project goal is to remove them over time.
- New code satisfies rules directly: named private constants instead of magic numbers,
  private helpers instead of long/complex methods.
- Baseline IDs are signature-keyed, so moving or deleting code strands entries. Regenerate
  with `./gradlew detektBaseline`; the baseline diff must only remove or update entries,
  never add new ones.

## Code conventions

- Surgical diffs: touch only what the task requires. Don't reformat, inline helpers,
  restructure, or "improve" adjacent working code.
- Match surrounding style, naming, and comment density. Comments state constraints the
  code can't show — never narrate what the next line does.
- Tests: JUnit4 + mockito-kotlin, backtick function names, temp dirs via
  `java.nio.file.Files`. Reuse existing fakes (`NoOpChapterDownloadQueue`,
  `InMemoryPermanentFailureStore`, `RecordingChapterDownloadQueue`). Robolectric caveat:
  `android.graphics` decoding (BitmapFactory / BitmapRegionDecoder) is unreliable in unit
  tests — don't assert on decoded bitmaps.
- No new dependencies unless the task explicitly asks for them.

## Architecture orientation (downloads/reader — the most-touched area)

- Downloaded web chapters live ONLY in the manifest store
  (`filesDir/downloads/web_chapters_v2/{urlHash}/manifest.json` + `images/`,
  `WebOfflineChapterStore`). The cache tiers (`cacheDir/html_cache`, `media_cache`) are
  LRU-evictable; download storage is never trimmed.
- "Downloaded" truth is `PrefetchResult.isStrictOfflineReady()`. The DB flag
  `LibraryItem.isDownloaded` is only ever promoted to true by `DownloadStatusReconciler`;
  direct demotion to false is allowed only immediately after clearing that download from
  disk (auto-deletion, the legacy-pipeline reset). Anywhere else, inspect first and demote
  only when `contentRepository.inspectDownload(url).isStrictOfflineReady()` is false.
- UI badge state (`LibraryDownloadStates.chapterCacheStates`): WorkManager emissions
  supply in-progress states only; disk inspects are the only writer of terminal states.
  Don't bypass this arbitration.
- Offline chapters serve `file://` image URIs — any image resolver keyed by URL must
  handle the `file:` scheme (see `ReaderImageTileFetcher.localFile`).

## Ground rules

- Never `git commit`, push, or branch — the coordinator reviews diffs and commits.
- End task reports with a fenced `===DIGEST===` block: files changed, key decisions,
  one paragraph of context for the next step.
