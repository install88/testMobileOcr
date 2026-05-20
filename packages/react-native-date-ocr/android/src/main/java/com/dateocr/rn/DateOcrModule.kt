package com.dateocr.rn

import android.graphics.BitmapFactory
import android.util.Log
import com.example.foodocr.offline.OfflineDatePipeline
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import java.io.File
import java.util.concurrent.Executors

/**
 * React Native bridge module that exposes the YOLO + PPOCR date pipeline
 * with an API shape compatible with @gutenye/ocr-react-native, so existing
 * callers (e.g. ShelfLifeScanScreen.js) can swap the import in one line.
 *
 * JS API (mirrors gutenye):
 *   await NativeModules.RNDateOcr.create({ models: { detectionPath, recognitionPath, dictionaryPath }, isDebug })
 *   await NativeModules.RNDateOcr.detect(imagePath) -> Array<{ text, score, frame: { top, left, width, height } }>
 *   await NativeModules.RNDateOcr.close()
 */
class DateOcrModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    @Volatile
    private var pipeline: OfflineDatePipeline? = null

    private val executor = Executors.newSingleThreadExecutor()

    override fun getName(): String = NAME

    @ReactMethod
    fun create(options: ReadableMap, promise: Promise) {
        executor.execute {
            try {
                val modelsMap = if (options.hasKey("models")) options.getMap("models") else null
                val yoloPath = modelsMap?.takeIf { it.hasKey("detectionPath") }?.getString("detectionPath")
                val recPath = modelsMap?.takeIf { it.hasKey("recognitionPath") }?.getString("recognitionPath")
                val keysPath = modelsMap?.takeIf { it.hasKey("dictionaryPath") }?.getString("dictionaryPath")

                if (yoloPath.isNullOrBlank() || recPath.isNullOrBlank() || keysPath.isNullOrBlank()) {
                    promise.reject(
                        "E_MISSING_MODEL_PATHS",
                        "Required model paths missing. Provide options.models.{detectionPath,recognitionPath,dictionaryPath}.",
                    )
                    return@execute
                }

                val missing = listOf(yoloPath, recPath, keysPath).filter { !File(it).exists() }
                if (missing.isNotEmpty()) {
                    promise.reject(
                        "E_MODEL_FILE_NOT_FOUND",
                        "Model files not found on disk: ${missing.joinToString()}",
                    )
                    return@execute
                }

                pipeline?.close()
                SharedPipelineHolder.current = null
                val newPipeline = OfflineDatePipeline.createFromFiles(
                    yoloModelPath = yoloPath,
                    recModelPath = recPath,
                    recKeysPath = keysPath,
                )
                pipeline = newPipeline
                // Publish to the shared holder so the vision-camera Frame
                // Processor Plugin (live preview path) sees the same instance.
                SharedPipelineHolder.current = newPipeline
                if (options.hasKey("isDebug") && options.getBoolean("isDebug")) {
                    Log.i(TAG, "Pipeline created (yolo=$yoloPath, rec=$recPath, keys=$keysPath)")
                }
                promise.resolve(null)
            } catch (error: Throwable) {
                Log.e(TAG, "create() failed", error)
                promise.reject("E_CREATE_FAILED", error.message ?: error::class.java.simpleName, error)
            }
        }
    }

    @ReactMethod
    fun detect(rawImagePath: String, promise: Promise) {
        executor.execute {
            try {
                val current = pipeline
                if (current == null) {
                    promise.reject(
                        "E_NOT_INITIALIZED",
                        "Pipeline not initialized. Call create() first.",
                    )
                    return@execute
                }

                val cleanedPath = rawImagePath.removePrefix("file://")
                val file = File(cleanedPath)
                if (!file.exists()) {
                    promise.reject("E_IMAGE_NOT_FOUND", "Image file not found: $cleanedPath")
                    return@execute
                }

                val bitmap = BitmapFactory.decodeFile(cleanedPath)
                if (bitmap == null) {
                    promise.reject("E_IMAGE_DECODE_FAILED", "Failed to decode image: $cleanedPath")
                    return@execute
                }

                val result = current.analyze(bitmap)
                val array = Arguments.createArray()

                // Build a TextLine[] response matching gutenye's shape:
                //   { text: string, score: number, frame: { top, left, width, height } }
                for (candidate in result.candidates) {
                    val text = candidate.recognition.text.trim()
                    if (text.isEmpty()) continue

                    val box = candidate.detection.bbox
                    val frame: WritableMap = Arguments.createMap().apply {
                        putDouble("top", box.top.toDouble())
                        putDouble("left", box.left.toDouble())
                        putDouble("width", (box.right - box.left).toDouble())
                        putDouble("height", (box.bottom - box.top).toDouble())
                    }

                    val line: WritableMap = Arguments.createMap().apply {
                        putString("text", text)
                        putDouble("score", candidate.recognition.confidence.toDouble())
                        putMap("frame", frame)
                    }
                    array.pushMap(line)
                }

                bitmap.recycle()
                promise.resolve(array)
            } catch (error: Throwable) {
                Log.e(TAG, "detect() failed", error)
                promise.reject("E_DETECT_FAILED", error.message ?: error::class.java.simpleName, error)
            }
        }
    }

    @ReactMethod
    fun close(promise: Promise) {
        executor.execute {
            try {
                SharedPipelineHolder.current = null
                pipeline?.close()
                pipeline = null
                promise.resolve(null)
            } catch (error: Throwable) {
                promise.reject("E_CLOSE_FAILED", error.message ?: error::class.java.simpleName, error)
            }
        }
    }

    override fun invalidate() {
        super.invalidate()
        try {
            SharedPipelineHolder.current = null
            pipeline?.close()
            pipeline = null
        } catch (_: Throwable) {
            // ignore
        }
        executor.shutdown()
    }

    companion object {
        const val NAME = "RNDateOcr"
        private const val TAG = "DateOcrModule"
    }
}
