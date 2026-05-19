package com.example.foodocr.offline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Application-scoped singleton for OfflineDatePipeline.
 *
 * Loading the YOLO + PPOCR ONNX sessions costs 1-2 seconds and ~21 MB of asset I/O.
 * Each Activity used to call OfflineDatePipeline.create() on its main thread in onCreate,
 * blocking the UI on every navigation. By caching one instance against the Application
 * context we pay that cost once per process and serve it instantly thereafter.
 *
 * OfflineDatePipeline.analyze* methods are @Synchronized, so concurrent callers serialize
 * on the pipeline instance — safe but currently irrelevant since both Activities
 * already throttle to one inference at a time.
 */
object OfflineDatePipelineProvider {
    @Volatile
    private var instance: OfflineDatePipeline? = null

    fun isLoaded(): Boolean = instance != null

    /**
     * Loads the pipeline on the IO dispatcher. Subsequent callers receive the cached instance
     * without re-loading. Throws if model assets are missing.
     */
    suspend fun get(context: Context): OfflineDatePipeline {
        instance?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(this@OfflineDatePipelineProvider) {
                instance ?: OfflineDatePipeline.create(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
