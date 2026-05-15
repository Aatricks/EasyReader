# Contributing

Thanks for considering a contribution. The basics that aren't obvious from `README.md`:

## Local setup

1. JDK 17, Android SDK 36, AGP 8.13 (the wrapper bootstraps everything else).
2. Default workflow for PRs is the `standardDebug` variant:
   ```bash
   ./gradlew :app:lintStandardDebug :app:testStandardDebugUnitTest :app:detekt :app:assembleStandardDebug
   ```
3. The `aiDebug` variant pulls `llmedge` from Maven Central and is only validated nightly / on `main`.

## Keystore hygiene

Release signing material (`keystore.properties`, `keystore_base64.txt`, `keystore/*.jks`) must **never** be committed. `.gitignore` covers the standard names, and `scripts/pre-commit-keystore-guard.sh` enforces the same patterns at commit time. Install the hook:

```bash
ln -sf "$(pwd)/scripts/pre-commit-keystore-guard.sh" .git/hooks/pre-commit
```

If you need to test release signing locally, keep the keystore outside the repo (e.g., `~/.easyreader/release.jks`) and point `keystore.properties` at it.

## Code style

- Kotlin official style; `:app:detekt` runs against the committed baseline (`app/detekt-baseline.xml`). Regenerate with `./gradlew :app:detektBaseline` if you intentionally accept new findings.
- Android lint baseline lives at `app/lint-baseline.xml`; the build is set to `abortOnError = true`. Add fixes inline, or regenerate the baseline with `./gradlew :app:updateLintBaseline`.

## Tests

- Unit tests: `:app:testStandardDebugUnitTest` (also `:app:testAiDebugUnitTest` for the AI flavor).
- Instrumented tests are wired in CI behind `android-instrumented-test`; locally run `./gradlew :app:connectedStandardDebugAndroidTest` with an emulator attached.
- Benchmark suite lives under `app/src/benchmark/java`. Opt-in via:
  ```bash
  ./gradlew :app:benchmark -PrunBenchmarks=true
  ```

## Security

See `SECURITY.md` for the disclosure process. Anything touching `UrlSecurity`, `SafeDns`, `SafeRedirectInterceptor`, `WebViewUtils`, deep-link routing, or backup rules deserves a security-minded review and ideally a unit test covering the new edge.
