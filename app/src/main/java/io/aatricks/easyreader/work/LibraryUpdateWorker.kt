package io.aatricks.easyreader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.repository.ExploreRepository
import io.aatricks.easyreader.data.repository.LibraryRepository
import java.util.concurrent.TimeUnit

/**
 * Periodic library-update worker. Replaces the cold-start refresh that used to run
 * inside MainActivity.onCreate: a 6-hour periodic job with UNMETERED constraint
 * means the refresh only fires when the device is on WiFi and the OS schedules it,
 * not in the synchronous launch path.
 */
class LibraryUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun libraryRepository(): LibraryRepository
        fun exploreRepository(): ExploreRepository
        fun preferencesManager(): PreferencesManager
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val ignoreThreshold = inputData.getBoolean(KEY_IGNORE_THRESHOLD, false)
        return runCatching {
            deps.libraryRepository().refreshLibraryUpdates(deps.exploreRepository(), ignoreThreshold)
            deps.preferencesManager().lastUpdateCheckTime = System.currentTimeMillis()
            Result.success()
        }.getOrElse { e ->
            android.util.Log.w(TAG, "library refresh worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LibraryUpdateWorker"
        private const val WORK_NAME = "library_update_periodic"
        private const val ONESHOT_WORK_NAME = "library_update_oneshot"
        const val KEY_IGNORE_THRESHOLD = "ignore_threshold"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Run a one-off update check immediately (e.g. right after a library import).
         *
         * Unlike the periodic job this checks every library item regardless of the
         * recent-activity threshold, so freshly restored finished series can surface their
         * NEW pills. It only requires a network connection (not WiFi) so it runs promptly.
         */
        fun runOnce(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_IGNORE_THRESHOLD to true))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
