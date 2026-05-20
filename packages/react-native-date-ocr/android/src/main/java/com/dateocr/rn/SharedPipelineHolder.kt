package com.dateocr.rn

import com.example.foodocr.offline.OfflineDatePipeline

/**
 * Process-scope handle to the currently-active [OfflineDatePipeline] instance.
 *
 * Two entry points need read access to the same pipeline:
 *   1. [DateOcrModule.detect] — static image OCR, invoked from JS via the
 *      standard React Native bridge (e.g. after a captured photo).
 *   2. [DateOcrFrameProcessorPlugin.callback] — live camera frame analysis,
 *      invoked per frame from a vision-camera worklet thread.
 *
 * Sharing a single pipeline avoids loading the ONNX models twice (≈22 MB on disk
 * each load) and keeps the worklet path cheap to invoke at 3+ FPS.
 *
 * Lifecycle is owned by [DateOcrModule]:
 *   - `Ocr.create()` writes the new pipeline into [current] after constructing it.
 *   - `Ocr.close()` clears [current] (and disposes the pipeline).
 *
 * The frame processor plugin only ever READS [current]; if it's null (pipeline
 * not yet created, or already closed) the worklet returns null and the JS side
 * sees no detections that tick.
 */
object SharedPipelineHolder {
    @Volatile
    var current: OfflineDatePipeline? = null
}
