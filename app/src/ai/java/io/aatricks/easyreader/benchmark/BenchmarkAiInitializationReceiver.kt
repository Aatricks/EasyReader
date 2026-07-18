package io.aatricks.easyreader.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.aatricks.easyreader.data.repository.summary.SummaryEngine
import io.aatricks.llmedge.huggingface.HuggingFaceHub
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class BenchmarkAiInitializationReceiver : BroadcastReceiver() {

    @Inject lateinit var summaryEngine: SummaryEngine

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK -> reportModelState(context)
            ACTION_INITIALIZE -> initializeEngine()
            else -> reportFailure("unsupported action")
        }
    }

    private fun reportModelState(context: Context) {
        val isCached = HuggingFaceHub.listCachedModels(context).any { modelDirectory ->
            modelDirectory.name.contains(MODEL_DIRECTORY_MARKER, ignoreCase = true) &&
                modelDirectory.walkTopDown().any { file ->
                    file.isFile && file.extension.equals("gguf", ignoreCase = true) && file.length() > 0L
                }
        }
        if (isCached) {
            setResultCode(Activity.RESULT_OK)
            setResultData("READY:model-cached")
        } else {
            reportFailure("model-not-cached")
        }
    }

    private fun initializeEngine() {
        val result = runBlocking(Dispatchers.IO) { summaryEngine.initialize() }
        result.onSuccess {
            setResultCode(Activity.RESULT_OK)
            setResultData("READY:ai-initialized")
        }.onFailure { throwable ->
            reportFailure(throwable.message ?: "initialization-failed")
        }
    }

    private fun reportFailure(reason: String) {
        setResultCode(Activity.RESULT_CANCELED)
        setResultData("ERROR:$reason")
    }

    private companion object {
        private const val ACTION_CHECK = "io.aatricks.easyreader.benchmark.CHECK_AI_MODEL"
        private const val ACTION_INITIALIZE = "io.aatricks.easyreader.benchmark.INITIALIZE_AI"
        private const val MODEL_DIRECTORY_MARKER = "Qwen3-0.6B-GGUF"
    }
}
