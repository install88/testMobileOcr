package com.example.foodocr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
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
import com.example.foodocr.offline.OfflineOnnxDateAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var debugView: TextView
    private lateinit var mfgResultView: TextView
    private lateinit var expResultView: TextView
    private lateinit var cameraExecutor: ExecutorService

    private val manufactureVotes = WeightedVoteBuffer()
    private val expiryVotes = WeightedVoteBuffer()

    private var dateAnalyzer: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        statusView = findViewById(R.id.statusText)
        hintView = findViewById(R.id.hintText)
        debugView = findViewById(R.id.debugText)
        mfgResultView = findViewById(R.id.mfgResult)
        expResultView = findViewById(R.id.expResult)

        viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        viewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER

        cameraExecutor = Executors.newSingleThreadExecutor()

        renderVoteState()

        if (allPermissionsGranted()) {
            bootstrap()
        } else {
            statusView.text = "等待相機權限..."
            hintView.text = "請允許相機權限後，讓日期區域停在畫面中央"
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun bootstrap() {
        if (!AssetModelRepository.hasRequiredAssets(this, OfflineModelAssets())) {
            statusView.text = "離線模型缺失"
            hintView.text = "請重新安裝 App，模型資源不完整"
            mfgResultView.setTextColor(Color.RED)
            expResultView.setTextColor(Color.RED)
            return
        }

        if (OfflineDatePipelineProvider.isLoaded()) {
            statusView.text = "Offline ONNX mode"
        } else {
            statusView.text = "載入模型中..."
            hintView.text = "首次啟動需要約 1-2 秒"
        }

        lifecycleScope.launch {
            try {
                val pipeline = OfflineDatePipelineProvider.get(this@MainActivity)
                startCamera(pipeline)
            } catch (error: Exception) {
                Log.e(TAG, "模型載入失敗", error)
                statusView.text = "模型載入失敗"
                hintView.text = error.message?.take(160) ?: error::class.java.simpleName
                mfgResultView.setTextColor(Color.RED)
                expResultView.setTextColor(Color.RED)
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera(pipeline: OfflineDatePipeline) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = viewFinder.surfaceProvider
                }

                dateAnalyzer?.close()
                val analyzer = OfflineOnnxDateAnalyzer(pipeline) { result -> handleFrameResult(result) }
                dateAnalyzer = analyzer

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor, analyzer)
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                    statusView.text = "正在掃描日期..."
                    hintView.text = "請讓日期區域佔據畫面中間，並停穩 0.5 秒"
                } catch (exc: Exception) {
                    Log.e(TAG, "相機綁定失敗", exc)
                    statusView.text = "相機啟動失敗"
                    hintView.text = "請重新開啟 App 或確認相機沒有被其他程式占用"
                    mfgResultView.setTextColor(Color.RED)
                    expResultView.setTextColor(Color.RED)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun handleFrameResult(result: FrameAnalysis) {
        result.manufactureObservation?.let(manufactureVotes::add)
        result.expiryObservation?.let(expiryVotes::add)

        val manufactureState = manufactureVotes.snapshot()
        val expiryState = expiryVotes.snapshot()

        runOnUiThread {
            renderRoleState(mfgResultView, "MFG", manufactureState, Color.parseColor("#64FFDA"))
            renderRoleState(expResultView, "EXP", expiryState, Color.parseColor("#7CFF6B"))

            statusView.text = result.statusMessage
            hintView.text = result.quality.hint
            debugView.text = result.debugText.ifBlank { "DBG: -" }
        }
    }

    private fun renderVoteState() {
        renderRoleState(mfgResultView, "MFG", manufactureVotes.snapshot(), Color.parseColor("#64FFDA"))
        renderRoleState(expResultView, "EXP", expiryVotes.snapshot(), Color.parseColor("#7CFF6B"))
    }

    private fun renderRoleState(
        targetView: TextView,
        label: String,
        state: VoteState,
        stableColor: Int,
    ) {
        when {
            state.stableDate != null -> {
                targetView.text = "$label: ${state.stableDate}"
                targetView.setTextColor(stableColor)
            }
            state.pendingDate != null -> {
                targetView.text = "$label: 判定中 ${state.pendingDate}"
                targetView.setTextColor(Color.parseColor("#FFD54F"))
            }
            else -> {
                targetView.text = "$label: 掃描中..."
                targetView.setTextColor(Color.WHITE)
            }
        }
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
                statusView.text = "需要相機權限才能掃描日期"
                hintView.text = "請到系統設定開啟相機權限後再試一次"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dateAnalyzer?.close()
        cameraExecutor.shutdown()
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private const val TAG = "FoodOCR"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
