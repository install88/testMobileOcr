package com.example.foodocr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import kotlin.math.max
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.foodocr.offline.AssetModelRepository
import com.example.foodocr.offline.OfflineDatePipeline
import com.example.foodocr.offline.OfflineDatePipelineProvider
import com.example.foodocr.offline.OfflineModelAssets
import com.example.foodocr.offline.cropToScanGuideRoi
import com.example.foodocr.offline.toBitmapFromJpeg
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

class SnapActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var mfgResultView: TextView
    private lateinit var expResultView: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnRetake: Button

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var pipeline: OfflineDatePipeline? = null
    private val analyzing = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snap)

        viewFinder = findViewById(R.id.viewFinder)
        statusView = findViewById(R.id.statusText)
        hintView = findViewById(R.id.hintText)
        mfgResultView = findViewById(R.id.mfgResult)
        expResultView = findViewById(R.id.expResult)
        btnCapture = findViewById(R.id.btnCapture)
        btnRetake = findViewById(R.id.btnRetake)

        viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        viewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (!AssetModelRepository.hasRequiredAssets(this, OfflineModelAssets())) {
            statusView.text = "離線模型缺失"
            hintView.text = "請重新安裝 App，模型資源不完整"
            btnCapture.isEnabled = false
            return
        }

        btnCapture.setOnClickListener { onCaptureClicked() }
        btnRetake.setOnClickListener { resetForRetake() }
        btnCapture.isEnabled = false

        if (allPermissionsGranted()) {
            bootstrap()
        } else {
            statusView.text = "等待相機權限..."
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun bootstrap() {
        if (OfflineDatePipelineProvider.isLoaded()) {
            statusView.text = "對焦後按下拍攝鈕"
            hintView.text = "讓日期區域停在掃描框內，確認清楚後按下「拍攝」"
        } else {
            statusView.text = "載入模型中..."
            hintView.text = "首次啟動需要約 1-2 秒"
        }

        lifecycleScope.launch {
            try {
                pipeline = OfflineDatePipelineProvider.get(this@SnapActivity)
                startCamera()
                btnCapture.isEnabled = true
                if (statusView.text == "載入模型中...") {
                    statusView.text = "對焦後按下拍攝鈕"
                    hintView.text = "讓日期區域停在掃描框內，確認清楚後按下「拍攝」"
                }
            } catch (error: Exception) {
                Log.e(TAG, "模型載入失敗", error)
                statusView.text = "模型載入失敗"
                hintView.text = error.message?.take(160) ?: error::class.java.simpleName
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = viewFinder.surfaceProvider
                }

                val capture = ImageCapture.Builder()
                    // MAXIMIZE_QUALITY: wait for AF/AE convergence + multi-frame merge.
                    // Adds ~200-500ms latency but yields much sharper images,
                    // which matters when YOLO is trained on lower-res images.
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                imageCapture = capture

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    statusView.text = "對焦後按下拍攝鈕"
                    hintView.text = "讓日期區域停在掃描框內，確認清楚後按下「拍攝」"
                } catch (exc: Exception) {
                    Log.e(TAG, "相機綁定失敗", exc)
                    statusView.text = "相機啟動失敗"
                    hintView.text = "請重新開啟 App 或確認相機沒有被其他程式占用"
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun onCaptureClicked() {
        val capture = imageCapture ?: return
        if (!analyzing.compareAndSet(false, true)) {
            return
        }
        btnCapture.isEnabled = false
        btnRetake.isEnabled = false
        statusView.text = "拍攝中..."
        hintView.text = "請保持手機穩定"

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        processCapturedImage(image)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍攝失敗", exception)
                    runOnUiThread {
                        statusView.text = "拍攝失敗：${exception.imageCaptureError}"
                        hintView.text = "請再試一次"
                        analyzing.set(false)
                        btnCapture.isEnabled = true
                    }
                }
            },
        )
    }

    private fun processCapturedImage(image: ImageProxy) {
        val pipeline = pipeline
        if (pipeline == null) {
            runOnUiThread {
                statusView.text = "Pipeline 未初始化"
                analyzing.set(false)
                btnCapture.isEnabled = true
            }
            return
        }
        val bitmap = image.toBitmapFromJpeg()
        if (bitmap == null) {
            runOnUiThread {
                statusView.text = "影像解碼失敗"
                hintView.text = "請再試一次"
                analyzing.set(false)
                btnCapture.isEnabled = true
            }
            return
        }

        val started = android.os.SystemClock.elapsedRealtime()
        // Defensive downscale: bring high-res ImageCapture output (4K+) closer to
        // the YOLO training distribution (~600-720p). Without this, fine date
        // details get aliased away during YOLO's internal resize to 640x640.
        val maxSide = max(bitmap.width, bitmap.height)
        val downscaled = if (maxSide > MAX_INPUT_SIDE) {
            val ratio = MAX_INPUT_SIDE.toFloat() / maxSide
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true,
            )
        } else bitmap
        val roiBitmap = downscaled.cropToScanGuideRoi()
        val result = try {
            pipeline.analyzeForCamera(roiBitmap)
        } catch (error: Exception) {
            Log.e(TAG, "Pipeline 失敗", error)
            null
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - started

        runOnUiThread {
            if (result == null) {
                statusView.text = "辨識失敗"
                hintView.text = "請重新拍攝"
                analyzing.set(false)
                btnCapture.isEnabled = true
                btnRetake.isEnabled = true
                return@runOnUiThread
            }

            val mfg = result.manufactureObservation
            val exp = result.expiryObservation

            renderResult(mfgResultView, "MFG", mfg?.normalizedDate, Color.parseColor("#64FFDA"))
            renderResult(expResultView, "EXP", exp?.normalizedDate, Color.parseColor("#7CFF6B"))

            statusView.text = when {
                mfg != null && exp != null -> "辨識完成（${elapsed}ms）"
                mfg != null || exp != null -> "僅辨識到單一日期（${elapsed}ms）"
                else -> "未偵測到日期（${elapsed}ms）"
            }
            hintView.text = if (mfg == null && exp == null) {
                "請調整角度或對焦後重拍"
            } else {
                "若結果不正確，請按「重拍」"
            }

            analyzing.set(false)
            btnCapture.isEnabled = false
            btnRetake.isEnabled = true
        }
    }

    private fun renderResult(view: TextView, label: String, date: String?, color: Int) {
        if (date != null) {
            view.text = "$label: $date"
            view.setTextColor(color)
        } else {
            view.text = "$label: 未偵測"
            view.setTextColor(Color.parseColor("#FF8A80"))
        }
    }

    private fun resetForRetake() {
        mfgResultView.text = "MFG: -"
        mfgResultView.setTextColor(Color.parseColor("#64FFDA"))
        expResultView.text = "EXP: -"
        expResultView.setTextColor(Color.parseColor("#7CFF6B"))
        statusView.text = "對焦後按下拍攝鈕"
        hintView.text = "讓日期區域停在掃描框內，確認清楚後按下「拍攝」"
        btnCapture.isEnabled = true
        btnRetake.isEnabled = false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                bootstrap()
            } else {
                statusView.text = "需要相機權限才能拍攝"
                hintView.text = "請到系統設定開啟相機權限後再試一次"
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Pipeline is owned by OfflineDatePipelineProvider; do not close here.
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "SnapActivity"
        private const val REQUEST_CODE_PERMISSIONS = 11
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        // Max bitmap side fed to pipeline. 1280px keeps detail without overwhelming
        // YOLO's 640x640 input space. Matches roughly what auto-detect mode sees.
        private const val MAX_INPUT_SIDE = 1280
    }
}
