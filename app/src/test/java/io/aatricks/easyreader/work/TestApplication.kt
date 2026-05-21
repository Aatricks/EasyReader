package io.aatricks.easyreader.work

import android.app.Application

/**
 * Minimal Application stand-in for Robolectric tests that need WorkManager but don't want
 * to bootstrap Hilt. Use with `@Config(application = TestApplication::class)`.
 */
class TestApplication : Application()
