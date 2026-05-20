package com.example.foodocr.offline

import android.graphics.Bitmap
import com.example.foodocr.ScanGuide

/**
 * Crop the bitmap to the [ScanGuide] ROI rectangle.
 *
 * Used by:
 *   - testMobileOcr app's ImageAnalysis analyzer (preview frames)
 *   - testMobileOcr SnapActivity capture flow
 *   - vision-camera Frame Processor Plugin (live preview from host RN app)
 *
 * The standalone app version of this file (in app/src/main/java) also
 * provides YUV → Bitmap helpers used by CameraX paths; the module
 * intentionally keeps only this single helper since the Frame Processor
 * Plugin has its own inline YUV→Bitmap conversion.
 */
fun Bitmap.cropToScanGuideRoi(): Bitmap {
    val roi = ScanGuide.roi(width, height)
    return Bitmap.createBitmap(this, roi.left, roi.top, roi.width(), roi.height())
}
