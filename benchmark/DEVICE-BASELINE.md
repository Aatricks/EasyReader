# Physical-device benchmark baseline

Reference device: Samsung Galaxy S22 (SM-S901B, Exynos 2200), Android 16 / API 36.
Build: minified `standardBenchmarkRelease` (release-derived, debug-signed), AGP 9.2.1,
benchmark-macro 1.4.1. Captured 2026-07-18, thermal status 0 throughout, device charging.
Iterations: 5 (startup) / 3 (journeys). Regression budget: fail a change that degrades
median or P95 by more than 10%, or that introduces frozen frames (>700 ms) in these
deterministic offline journeys.

## Startup

| Benchmark | timeToInitialDisplay median | min | max |
|---|---:|---:|---:|
| startupCold | 285.8 ms | 276.6 ms | 340.8 ms |
| startupWarm | 70.6 ms | 67.4 ms | 124.4 ms |

## Journeys (offline fixtures)

| Benchmark | frame CPU P50 | P95 | P99 | overrun P95 | peak heap | peak RSS anon |
|---|---:|---:|---:|---:|---:|---:|
| scrollTallManhwaTiles (13-tile image) | 6.71 ms | 9.01 ms | 10.8 ms | 2.29 ms | 34.1 MB | 62.5 MB |
| expandFiveHundredChapterLibrary | 6.71 ms | 10.8 ms | 12.9 ms | 4.35 ms | 53.5 MB | 84.5 MB |
| paginateThousandParagraphs | 4.61 ms | 7.27 ms | 9.40 ms | 2.56 ms | 49.9 MB | 80.9 MB |
| paginateFiveHundredParagraphs | 6.80 ms | 13.5 ms | 14.8 ms | 10.8 ms | 45.4 MB | 74.9 MB |
| openTwentyPagePdf | 1.85 ms | 9.77 ms | 112 ms | 7.81 ms | 32.4 MB | 57.4 MB |
| restoredThousandParagraphReaderFirstContent | 1.96 ms | 14.4 ms | 113 ms | 59.6 ms | 23.6 MB | 47.6 MB |

Notes:
- The P99 spikes on the PDF and restored-reader journeys are the single first frame after
  content load lands; there are no frozen frames in any journey.
- `timeToInitialDisplay` for content journeys (paged readers, PDF, restored reader) sits at
  275–296 ms median — cold process start plus content restore.
- GPU memory counter is only reported by the manhwa (7.7 MB) and library (39.2 MB) journeys.

## AI flavor (minified aiBenchmarkRelease)

All journeys pass with the same fixtures; the model-initialization benchmark skips
honestly when no cached model is present on the device. Representative medians:
cold startup 259.8 ms, warm 77.9 ms; frame CPU P95 stays between 9.5 ms (manhwa)
and 16.5 ms (1,000-paragraph pagination); peak heap and RSS match the standard
flavor within a few MB. The AI runtime only affects memory once a model is loaded.

Generated Baseline Profiles (captured on this device, committed under
`app/src/<flavor>Release/generated/baselineProfiles/`):

| Flavor | baseline-prof.txt | startup-prof.txt |
|---|---:|---:|
| standard | 33,681 rules | 21,188 rules |
| ai | 33,360 rules | 19,811 rules |

## Artifact sizes (minified benchmarkRelease, arm64)

| Artifact | bytes |
|---|---:|
| standard APK | 7,214,024 |
| AI APK | 188,393,932 |
| AI native libraries (llmedge/ONNX/Whisper stack) | 174,116,920 |

## Battery (measured 2026-07-18, S22, fake-unplug batterystats)

A scripted ~3-minute manhwa reading session (30 slow scrolls, minified standard build):

| Component | drain (mAh) | share |
|---|---:|---|
| Screen | 7.39 | dominant, ~9× the app's own cost |
| App process (all foreground CPU) | 0.82 | ≈17 mAh/h ≈ 0.5%/h of the 3405 mAh battery |
| App network/sensors/wakelocks | ≈0 | no wakelocks, no background CPU, no radio wakeups |

The app is battery-lean: the only periodic job (library update) is constrained to
6-hour/unmetered/battery-not-low, polls are bounded, image prefetch is conservative
(4 ahead, single speculative attempt), and the reader defaults to a true-black OLED
background. The dominant cost is the screen itself, which only user-facing settings
(brightness, dark theme — both already present) can influence.

## How to reproduce

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_SERIAL=<device>
./gradlew :benchmark:connectedStandardBenchmarkReleaseAndroidTest
./gradlew :app:generateStandardReleaseBaselineProfile
```

Run on the same physical device, plugged in, thermal status 0 (`dumpsys thermalservice`).
Benchmark journeys are driven by the build-only fixture receiver
(`io.aatricks.easyreader.benchmark.BenchmarkFixtureReceiver`); no network is used.
Enable Do Not Disturb on personal devices before running — heads-up notifications can
steal UI-automation taps mid-journey.
