package com.example.myapplication

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.os.SystemClock
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ========= 뷰 =========
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: GuideOverlayView
    private lateinit var captureButton: Button
    private lateinit var cameraContainer: FrameLayout

    private lateinit var resultContainer: FrameLayout
    private lateinit var thumbnailImageView: ImageView
    private lateinit var retryButton: Button
    private lateinit var summaryCardTextView: TextView
    private lateinit var tts: TextToSpeech
    private var lastSpokenText = ""

    // 줌/토치 UI
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var torchButton: ToggleButton

    // 가격 포지션 및 유사 제품 비교 UI
    private lateinit var kfiChipsHeaderTextView: TextView
    private lateinit var kfiChipsLayout: LinearLayout

    private lateinit var linearPriceGraphView: LinearPriceGraphView
    private lateinit var priceBadgeTextView: TextView
    private lateinit var compareDetailButton: Button
    private lateinit var priceCardLayout: LinearLayout

    private lateinit var comparisonContainer: FrameLayout
    private lateinit var comparisonTitleTextView: TextView
    private lateinit var comparisonItemsLayout: LinearLayout
    private lateinit var backToResultButton: Button
    private var lastPricePositionResult: PricePositionResult? = null

    // ========= 상태 =========
    private val REQUIRED_PERMISSION = Manifest.permission.CAMERA
    private lateinit var cameraExecutor: ExecutorService
    private var lastCroppedBitmap: Bitmap? = null

    private var boundCamera: Camera? = null
    private var torchOn: Boolean = false

    // 자동 스캔
    private val autoHandler = Handler(Looper.getMainLooper())
    private var isOcrBusy = false
    private var autoScanEnabled = true
    private var stableHitCount = 0
    private var lastDetectedCode: String? = null
    private var autoScanFailCount = 0

    // 상태 보존 (화면 회전 대응)
    private var isShowingResult = false
    private var savedLetterCode: String? = null
    private var savedNumberCode: String? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isShowingResult", isShowingResult)
        outState.putString("savedLetterCode", savedLetterCode)
        outState.putString("savedNumberCode", savedNumberCode)
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // ===== 루트 =====
        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ===== 카메라 컨테이너 =====
        cameraContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        overlayView = GuideOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 핀치 줌 지적 대응
        var lastScaleTime = 0L

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                lastScaleTime = SystemClock.elapsedRealtime()
                val cam = boundCamera ?: return false
                val currentZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                val newZoom = currentZoom * delta
                cam.cameraControl.setZoomRatio(newZoom)

                val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                val progress = (((newZoom - minZoom) / (maxZoom - minZoom)) * 100).toInt()
                if (::zoomSeekBar.isInitialized) {
                    zoomSeekBar.progress = progress.coerceIn(0, 100)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                super.onScaleEnd(detector)
                lastScaleTime = SystemClock.elapsedRealtime()
            }
        })

        previewView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)

            val isPinchActive = scaleDetector.isInProgress || (SystemClock.elapsedRealtime() - lastScaleTime < 400)

            if (!isPinchActive && event.pointerCount == 1 && event.action == MotionEvent.ACTION_UP) {
                v.performClick()
                handleTouchScan(event.x, event.y)
            }
            true
        }

        // 줌 슬라이더
        zoomSeekBar = SeekBar(this).apply {
            max = 100
            progress = 0
            val params = FrameLayout.LayoutParams(
                dpToPx(220),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.bottomMargin = dpToPx(110)
            layoutParams = params
        }
        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val cam = boundCamera ?: return
                    val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                    val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                    val targetZoom = minZoom + (maxZoom - minZoom) * (progress / 100f)
                    cam.cameraControl.setZoomRatio(targetZoom)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 조명 토글
        torchButton = ToggleButton(this).apply {
            textOn = "💡 플래시 ON"
            textOff = "💡 플래시 OFF"
            isChecked = false
            textSize = 13f
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.gravity = Gravity.TOP or Gravity.END
            params.topMargin = dpToPx(36)
            params.rightMargin = dpToPx(16)
            layoutParams = params
            setOnClickListener {
                torchOn = isChecked
                boundCamera?.cameraControl?.enableTorch(torchOn)
            }
        }

        // 촬영 버튼
        captureButton = Button(this).apply {
            text = "📸 스캔"
            textSize = 16f
            val params = FrameLayout.LayoutParams(
                dpToPx(120),
                dpToPx(48)
            )
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.bottomMargin = dpToPx(32)
            layoutParams = params
        }
        captureButton.setOnClickListener {
            captureAndRecognize(fromAuto = false)
        }

        cameraContainer.addView(previewView)
        cameraContainer.addView(overlayView)
        cameraContainer.addView(zoomSeekBar)
        cameraContainer.addView(torchButton)
        cameraContainer.addView(captureButton)

        // ===== 결과 컨테이너 =====
        resultContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        val resultScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val resultLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        // 상단: 썸네일 + 버튼들
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        thumbnailImageView = ImageView(this).apply {
            val params = LinearLayout.LayoutParams(0, dpToPx(200), 1f)
            params.marginEnd = dpToPx(12)
            layoutParams = params
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        thumbnailImageView.setOnClickListener { showFullImage() }

        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        }

        retryButton = Button(this).apply {
            text = "다시 촬영하기"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        retryButton.setOnClickListener { showCameraScreen() }

        val pdfReportButton = Button(this).apply {
            text = "📄 PDF 보고서 공유"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(8)
            }
        }
        pdfReportButton.setOnClickListener {
            cameraExecutor.execute {
                val db = AppDatabase.getDatabase(this)
                val records = kotlinx.coroutines.runBlocking { db.inspectionDao().getAllRecords() }
                runOnUiThread {
                    if (records.isEmpty()) {
                        Toast.makeText(this, "저장된 점검 이력이 없습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        PdfReportUtil.generateAndSharePdf(this, records)
                    }
                }
            }
        }

        buttonsLayout.addView(retryButton)
        buttonsLayout.addView(pdfReportButton)

        topRow.addView(thumbnailImageView)
        topRow.addView(buttonsLayout)

        val summaryCardTitle = TextView(this).apply {
            text = "🤖 AI 스마트 검수 요약"
            textSize = 16f
            setPadding(0, dpToPx(12), 0, dpToPx(4))
            setTypeface(null, Typeface.BOLD)
        }

        summaryCardTextView = TextView(this).apply {
            text = "• AI 분석 대기 중..."
            textSize = 15f
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            setBackgroundColor(0xFFE8F5E9.toInt())
        }

        val ttsButton = Button(this).apply {
            text = "🔊 결과 음성으로 듣기 (TTS)"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
        }
        ttsButton.setOnClickListener {
            speakLastResult()
        }

        // ===== KFI 중복 품목 선택 칩 UI =====
        kfiChipsHeaderTextView = TextView(this).apply {
            text = "⚠️ 본 합격표시로 여러 개의 KFI 등록 품목이 검색되었습니다. 점검 품목을 선택해 주세요:"
            textSize = 14f
            setTextColor(0xFFD84315.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dpToPx(8), 0, dpToPx(4))
            visibility = View.GONE
        }

        kfiChipsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            visibility = View.GONE
        }

        resultLayout.addView(topRow)
        resultLayout.addView(kfiChipsHeaderTextView)
        resultLayout.addView(kfiChipsLayout)
        resultLayout.addView(summaryCardTitle)
        resultLayout.addView(summaryCardTextView)
        resultLayout.addView(ttsButton)

        // ===== 시장 가격 포지션 분석 카드 =====
        priceCardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(16)
            }
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            setBackgroundColor(0xFFF5F5F5.toInt())
            visibility = View.GONE
        }

        val priceCardTitle = TextView(this).apply {
            text = "📊 동급 종별 시장 가격 포지션 분석"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(8))
        }

        linearPriceGraphView = LinearPriceGraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(80)
            )
        }

        priceBadgeTextView = TextView(this).apply {
            text = "💰 동급 시장 가격 분석 중..."
            textSize = 14f
            setTextColor(0xFF2E7D32.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dpToPx(6), 0, dpToPx(8))
        }

        compareDetailButton = Button(this).apply {
            text = "🔍 동급 유사 제품군 가격 비교 (자세히 보기) ➔"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        compareDetailButton.setOnClickListener {
            showComparisonPage()
        }

        priceCardLayout.addView(priceCardTitle)
        priceCardLayout.addView(linearPriceGraphView)
        priceCardLayout.addView(priceBadgeTextView)
        priceCardLayout.addView(compareDetailButton)

        resultLayout.addView(priceCardLayout)

        resultScrollView.addView(resultLayout)
        resultContainer.addView(resultScrollView)

        // ===== 전용 유사 제품군 가격 비교 화면 =====
        comparisonContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFFFFFFFF.toInt())
            visibility = View.GONE
        }

        val compScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val compLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        val compHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16)
            }
        }

        backToResultButton = Button(this).apply {
            text = "← 이전 페이지로 돌아가기"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        backToResultButton.setOnClickListener {
            showResultScreenFromComparison()
        }

        comparisonTitleTextView = TextView(this).apply {
            text = "📊 동급 유사 제품군 시장 가격 비교"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(dpToPx(12), 0, 0, 0)
        }

        compHeaderRow.addView(backToResultButton)
        compHeaderRow.addView(comparisonTitleTextView)

        comparisonItemsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        compLayout.addView(compHeaderRow)
        compLayout.addView(comparisonItemsLayout)
        compScrollView.addView(compLayout)
        comparisonContainer.addView(compScrollView)

        rootLayout.addView(cameraContainer)
        rootLayout.addView(resultContainer)
        rootLayout.addView(comparisonContainer)

        // TTS 초기화
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN)
            }
        }

        // CSV 데이터 초기 로컬 DB 적재 (오프라인 우선)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            CsvImporter.importCsvDataIfEmpty(applicationContext)
        }

        setContentView(rootLayout)

        // 권한 체크
        if (savedInstanceState != null) {
            isShowingResult = savedInstanceState.getBoolean("isShowingResult", false)
            savedLetterCode = savedInstanceState.getString("savedLetterCode")
            savedNumberCode = savedInstanceState.getString("savedNumberCode")
            if (isShowingResult && savedLetterCode != null && savedNumberCode != null) {
                showResultScreen()
                fetchKfiResult(savedLetterCode!!, savedNumberCode!!)
            }
        }

        if (ContextCompat.checkSelfPermission(this, REQUIRED_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission.launch(REQUIRED_PERMISSION)
        }
    }

    // ================= 카메라인증/설정 =================
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                boundCamera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
                )
                startAutoScanLoop()
            } catch (e: Exception) {
                Log.e("CameraX", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getCroppedBitmapFromPreview(): Bitmap? {
        val bitmap = previewView.bitmap ?: return null
        val guideRect = overlayView.getBoxRect()

        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return bitmap

        val scaleX = bitmap.width / viewWidth
        val scaleY = bitmap.height / viewHeight

        val cropLeft = (guideRect.left * scaleX).toInt().coerceIn(0, bitmap.width)
        val cropTop = (guideRect.top * scaleY).toInt().coerceIn(0, bitmap.height)
        val cropWidth = (guideRect.width() * scaleX).toInt().coerceAtMost(bitmap.width - cropLeft)
        val cropHeight = (guideRect.height() * scaleY).toInt().coerceAtMost(bitmap.height - cropTop)

        if (cropWidth <= 0 || cropHeight <= 0) return bitmap

        return Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
    }

    private fun enhanceBitmapForOcr(source: Bitmap): Bitmap {
        val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 흑백(그레이스케일) + 명암 대비(Contrast) 향상 (30% 제어)
        val contrast = 1.3f
        val translate = (-0.5f * contrast + 0.5f) * 255f

        val colorMatrix = ColorMatrix(
            floatArrayOf(
                0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, translate,
                0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, translate,
                0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return bitmap
    }

    // ================= 터치 포커스 & 동적 가변 크롭 스캔 =================
    private fun getTouchCroppedBitmap(screenX: Float, screenY: Float): Bitmap? {
        val bitmap = previewView.bitmap ?: return null
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return bitmap

        val scaleX = bitmap.width / viewWidth
        val scaleY = bitmap.height / viewHeight

        val bmpX = screenX * scaleX
        val bmpY = screenY * scaleY

        // 터치 지점 중심 동적 가변 크롭 박스 크기 (화면 가로/세로의 약 45% 영역)
        val cropSizeX = bitmap.width * 0.45f
        val cropSizeY = bitmap.height * 0.45f

        val cropLeft = (bmpX - cropSizeX / 2f).toInt().coerceIn(0, bitmap.width - 10)
        val cropTop = (bmpY - cropSizeY / 2f).toInt().coerceIn(0, bitmap.height - 10)
        val cropWidth = cropSizeX.toInt().coerceAtMost(bitmap.width - cropLeft)
        val cropHeight = cropSizeY.toInt().coerceAtMost(bitmap.height - cropTop)

        if (cropWidth <= 10 || cropHeight <= 10) return bitmap

        return Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
    }

    private fun handleTouchScan(screenX: Float, screenY: Float) {
        val cam = boundCamera
        if (cam != null) {
            try {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(screenX, screenY)
                val action = FocusMeteringAction.Builder(point).build()
                cam.cameraControl.startFocusAndMetering(action)
            } catch (e: Exception) {
                Log.e("CameraX", "Touch focus failed", e)
            }
        }

        overlayView.setTouchPoint(screenX, screenY)
        overlayView.setStatus("🎯 스티커 초점 조율 중...", true)

        val cropped = getTouchCroppedBitmap(screenX, screenY) ?: return
        val enhanced = enhanceBitmapForOcr(cropped)
        lastCroppedBitmap = enhanced
        thumbnailImageView.setImageBitmap(enhanced)

        runTextRecognitionOnBitmap(enhanced, fromAuto = false)
    }

    // ================= 수동/자동 촬영 공통 =================
    private fun captureAndRecognize(fromAuto: Boolean) {
        val cropped = getCroppedBitmapFromPreview()
        if (cropped == null) {
            if (!fromAuto) Toast.makeText(this, "화면 캡처에 실패했습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val enhanced = enhanceBitmapForOcr(cropped)
        lastCroppedBitmap = enhanced
        thumbnailImageView.setImageBitmap(enhanced)
        runTextRecognitionOnBitmap(enhanced, fromAuto)
    }

    // ================= 자동 스캔 =================
    private val autoRunnable = object : Runnable {
        override fun run() {
            if (!autoScanEnabled || resultContainer.visibility == View.VISIBLE) return

            if (!isOcrBusy) {
                captureAndRecognize(fromAuto = true)
            }

            autoHandler.postDelayed(this, 700)
        }
    }

    private fun startAutoScanLoop() {
        autoScanEnabled = true
        stableHitCount = 0
        lastDetectedCode = null
        autoScanFailCount = 0
        autoHandler.removeCallbacksAndMessages(null)
        autoHandler.postDelayed(autoRunnable, 900)
    }

    // ================= 하이브리드 바코드/QR + OCR + KFI조회 =================
    private fun fixLetters(s: String): String {
        val sb = StringBuilder()
        for (c in s.uppercase()) {
            val converted = when (c) {
                '0' -> 'O'
                '1' -> 'I'
                '2' -> 'Z'
                '5' -> 'S'
                '8' -> 'B'
                '6' -> 'G'
                else -> c
            }
            if (converted in 'A'..'Z') {
                sb.append(converted)
            }
        }
        return sb.toString()
    }

    private fun fixDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s.uppercase()) {
            val converted = when (c) {
                'O', 'Q' -> '0'
                'I', 'L', '|' -> '1'
                'Z' -> '2'
                'S' -> '5'
                'B' -> '8'
                'G' -> '6'
                'T' -> '7'
                else -> c
            }
            if (converted in '0'..'9') {
                sb.append(converted)
            }
        }
        return sb.toString()
    }

    private fun extractCleanLetters(s: String): String? {
        val raw = s.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        if (raw.length != 4) return null

        val fixed = fixLetters(raw)
        if (fixed.length != 4) return null

        // OCR 오인식 숫자('1'->'I', '0'->'O', '5'->'S', '8'->'B', '2'->'Z') 사전 허용 보정
        val pureLetters = raw.count { it in 'A'..'Z' }
        val confusableDigits = raw.count { it in listOf('1', '0', '5', '8', '2') }
        if (pureLetters + confusableDigits < 3) {
            return null
        }
        return fixed
    }

    private fun extractCleanDigits(s: String): String? {
        val raw = s.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        if (raw.length != 5) return null

        val fixed = fixDigits(raw)
        if (fixed.length != 5) return null

        val pureDigits = raw.count { it in '0'..'9' }
        val confusableLetters = raw.count { it in listOf('O', 'Q', 'I', 'L', '|', 'Z', 'S', 'B', 'G', 'T') }
        if (pureDigits + confusableLetters < 4) {
            return null
        }
        return fixed
    }

    private fun parseStickerCodeFromString(rawText: String): Pair<String, String>? {
        val cleaned = rawText.uppercase().replace(Regex("[^A-Z0-9]"), "")

        // 1. 정확히 영문 4자리 + 숫자 5자리 매칭
        val exactRegex = Regex("([A-Z]{4})(\\d{5})")
        val exactMatch = exactRegex.find(cleaned)
        if (exactMatch != null) {
            return exactMatch.groupValues[1] to exactMatch.groupValues[2]
        }

        // 2. OCR 오인식 문자 보정 후 매칭
        val candidateRegex = Regex("([A-Z0-9]{4})(\\d{5}|[A-Z0-9]{5})")
        val candidateMatches = candidateRegex.findAll(cleaned)
        for (m in candidateMatches) {
            val rawL = m.groupValues[1]
            val rawD = m.groupValues[2]
            val cleanL = extractCleanLetters(rawL)
            val cleanD = extractCleanDigits(rawD)
            if (cleanL != null && cleanD != null) {
                return cleanL to cleanD
            }
        }

        return null
    }

    private fun runTextRecognitionOnBitmap(croppedBitmap: Bitmap, fromAuto: Boolean) {
        if (isOcrBusy) return
        isOcrBusy = true

        runOcrFallback(croppedBitmap, fromAuto)
    }

    private fun runOcrFallback(croppedBitmap: Bitmap, fromAuto: Boolean) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val barcodeScanner = BarcodeScanning.getClient()

        val angles = intArrayOf(0, 180, 90, 270)

        fun tryAngle(index: Int, lastText: String) {
            if (index >= angles.size) {
                processOcrFailure(lastText, fromAuto)
                return
            }
            val angle = angles[index]

            // 물리적으로 Bitmap을 회전시켜 ML Kit에 전달 (0도면 원본 사용)
            val rotatedBitmap = if (angle == 0) {
                croppedBitmap
            } else {
                val matrix = Matrix().apply { postRotate(angle.toFloat()) }
                Bitmap.createBitmap(croppedBitmap, 0, 0, croppedBitmap.width, croppedBitmap.height, matrix, true)
            }

            val image = InputImage.fromBitmap(rotatedBitmap, 0)

            // 1. 회전된 비트맵으로 바코드 스캔 시도
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    var barcodeFound: Pair<String, String>? = null
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue ?: continue
                        val parsed = parseStickerCodeFromString(rawValue)
                        if (parsed != null) {
                            barcodeFound = parsed
                            break
                        }
                    }

                    if (barcodeFound != null) {
                        lastCroppedBitmap = rotatedBitmap
                        runOnUiThread { thumbnailImageView.setImageBitmap(rotatedBitmap) }
                        processOcrSuccess(barcodeFound, "Barcode", fromAuto)
                        return@addOnSuccessListener
                    }

                    // 2. 바코드 미감지시 회전된 비트맵으로 OCR 텍스트 인식 시도
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            val codeResult = extractStickerCodeFromVision(visionText, requireMark = fromAuto)
                            if (codeResult != null) {
                                lastCroppedBitmap = rotatedBitmap
                                runOnUiThread { thumbnailImageView.setImageBitmap(rotatedBitmap) }
                                processOcrSuccess(codeResult, visionText.text, fromAuto)
                            } else {
                                if (rotatedBitmap != croppedBitmap) {
                                    try { rotatedBitmap.recycle() } catch (_: Exception) {}
                                }
                                tryAngle(index + 1, visionText.text)
                            }
                        }
                        .addOnFailureListener {
                            if (rotatedBitmap != croppedBitmap) {
                                try { rotatedBitmap.recycle() } catch (_: Exception) {}
                            }
                            tryAngle(index + 1, lastText)
                        }
                }
                .addOnFailureListener {
                    // 바코드 스캔 실패시 OCR 진행
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            val codeResult = extractStickerCodeFromVision(visionText, requireMark = fromAuto)
                            if (codeResult != null) {
                                lastCroppedBitmap = rotatedBitmap
                                runOnUiThread { thumbnailImageView.setImageBitmap(rotatedBitmap) }
                                processOcrSuccess(codeResult, visionText.text, fromAuto)
                            } else {
                                if (rotatedBitmap != croppedBitmap) {
                                    try { rotatedBitmap.recycle() } catch (_: Exception) {}
                                }
                                tryAngle(index + 1, visionText.text)
                            }
                        }
                        .addOnFailureListener {
                            if (rotatedBitmap != croppedBitmap) {
                                try { rotatedBitmap.recycle() } catch (_: Exception) {}
                            }
                            tryAngle(index + 1, lastText)
                        }
                }
        }

        tryAngle(0, "")
    }

    private fun processOcrSuccess(codeResult: Pair<String, String>, fullText: String, fromAuto: Boolean) {
        val (letters, digits) = codeResult
        autoScanFailCount = 0

        if (fromAuto) {
            val key = "$letters-$digits"
            if (lastDetectedCode == key) stableHitCount++ else stableHitCount = 1
            lastDetectedCode = key

            overlayView.setStatus("✅ 인증번호 감지: $letters $digits ($stableHitCount/2)", true)

            if (stableHitCount < 2) {
                isOcrBusy = false
                return
            }
        }

        summaryCardTextView.text = "• KFI 합격표시 정보를 조회 중입니다..."
        fetchKfiResult(letters, digits)

        autoScanEnabled = false
        showResultScreen()
        isOcrBusy = false
    }

    private fun processOcrFailure(fullText: String, fromAuto: Boolean) {
        if (!fromAuto) {
            summaryCardTextView.text = buildString {
                appendLine("🔴 [판정: 인증번호 인식 실패]")
                appendLine("• 합격 표시: 찾지 못했습니다.")
                appendLine("• 형식승인번호: -")
                appendLine("• 스티커의 영문/숫자 인쇄 상태를 확인하거나 스티커 부분을 손가락으로 터치해주세요.")
            }.trim()
            autoScanEnabled = false
            showResultScreen()
        } else {
            stableHitCount = 0
            lastDetectedCode = null
            autoScanFailCount++
            if (autoScanFailCount >= 3) {
                overlayView.setStatus("💡 인식이 안 될 경우 스티커를 손가락으로 터치해주세요", false)
            } else {
                overlayView.setStatus("💡 스티커를 터치하거나 마크를 박스에 맞춰주세요", false)
            }
        }
        isOcrBusy = false
    }

    private fun isBackgroundPlateLine(rawText: String): Boolean {
        val t = rawText.uppercase()
        val plateKeywords = listOf(
            "등록", "인증", "제조", "TEL", "MODEL", "상호", "식별", "SPEC",
            "REM", "LTH", "LTD", "SERIAL", "NO.", "NO:", ":", "-", "032", "031", "02"
        )
        return plateKeywords.any { t.contains(it) }
    }

    private fun isGenuineMarkLine(rawLineText: String, rawBlockText: String): Boolean {
        val lText = rawLineText.uppercase().trim()
        val bText = rawBlockText.uppercase().trim()

        if (isBackgroundPlateLine(lText) || isBackgroundPlateLine(bText)) {
            return false
        }
        if (lText.length > 10) {
            return false
        }
        val markKeywords = listOf("KC", "KFI", "방염", "소방", "기술원", "합격")
        return markKeywords.any { lText.contains(it) }
    }

    private fun extractStickerCodeFromVision(visionText: Text, requireMark: Boolean = false): Pair<String, String>? {
        // 요소(Element) 및 정방향 공간 구조 분석 (정방향 이미지 기준: 마크 -> 영문4자리 -> 숫자5자리)
        data class TokenCandidate(
            val fixedText: String,
            val box: Rect,
            val centerX: Double,
            val centerY: Double
        )

        data class RawElement(val text: String, val box: Rect)
        val rawElements = mutableListOf<RawElement>()

        val letterCandidates = mutableListOf<TokenCandidate>()
        val digitCandidates = mutableListOf<TokenCandidate>()

        var markBox: Rect? = null

        for (block in visionText.textBlocks) {
            val blockText = block.text.uppercase()
            if (isBackgroundPlateLine(blockText)) {
                // 배경 제품 규격 명판 블록 전체 필터링 차단!
                continue
            }

            for (line in block.lines) {
                val lineText = line.text.uppercase()
                if (isBackgroundPlateLine(lineText)) {
                    // 배경 규격 명판 라인 필터링 차단!
                    continue
                }

                if (isGenuineMarkLine(lineText, blockText)) {
                    val box = line.boundingBox
                    if (box != null && (markBox == null || box.top < markBox.top)) {
                        markBox = box
                    }
                }

                val lineCleanL = extractCleanLetters(lineText)
                if (lineCleanL != null) {
                    val box = line.boundingBox
                    if (box != null) {
                        letterCandidates.add(
                            TokenCandidate(
                                lineCleanL, box,
                                box.centerX().toDouble(), box.centerY().toDouble()
                            )
                        )
                    }
                }
                val lineCleanD = extractCleanDigits(lineText)
                if (lineCleanD != null) {
                    val box = line.boundingBox
                    if (box != null) {
                        digitCandidates.add(
                            TokenCandidate(
                                lineCleanD, box,
                                box.centerX().toDouble(), box.centerY().toDouble()
                            )
                        )
                    }
                }

                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    val clean = element.text.uppercase().replace(Regex("[^A-Z0-9]"), "")
                    if (clean.isNotBlank()) {
                        rawElements.add(RawElement(clean, box))
                    }
                }
            }
        }

        // 자동 스캔(fromAuto) 모드인 경우 마크(KC, KFI, 방염 등)가 감지되지 않으면 성급한 오작동 방지를 위해 100% 차단!
        if (requireMark && markBox == null) {
            return null
        }

        // (1) 단일 토큰 후보 수집 (엄격한 순수 영문/숫자 비율 검증 적용)
        for (elem in rawElements) {
            val cleanL = extractCleanLetters(elem.text)
            if (cleanL != null) {
                letterCandidates.add(
                    TokenCandidate(
                        cleanL, elem.box,
                        elem.box.centerX().toDouble(), elem.box.centerY().toDouble()
                    )
                )
            }

            val cleanD = extractCleanDigits(elem.text)
            if (cleanD != null) {
                digitCandidates.add(
                    TokenCandidate(
                        cleanD, elem.box,
                        elem.box.centerX().toDouble(), elem.box.centerY().toDouble()
                    )
                )
            }
        }

        // (2) 동일 라인 수평 인접 토큰 결합 (예: 2+2 영문, 2+3 숫자 등)
        for (i in 0 until rawElements.size) {
            for (j in i + 1 until rawElements.size) {
                val e1 = rawElements[i]
                val e2 = rawElements[j]

                val yDiff = Math.abs(e1.box.centerY() - e2.box.centerY())
                val xGap = Math.abs(e1.box.centerX() - e2.box.centerX())

                if (yDiff < 30 && xGap < 150) {
                    val (leftE, rightE) = if (e1.box.left < e2.box.left) e1 to e2 else e2 to e1
                    val combinedText = leftE.text + rightE.text

                    val cleanL = extractCleanLetters(combinedText)
                    if (cleanL != null) {
                        val mergedBox = Rect(
                            Math.min(leftE.box.left, rightE.box.left),
                            Math.min(leftE.box.top, rightE.box.top),
                            Math.max(leftE.box.right, rightE.box.right),
                            Math.max(leftE.box.bottom, rightE.box.bottom)
                        )
                        letterCandidates.add(
                            TokenCandidate(
                                cleanL, mergedBox,
                                mergedBox.centerX().toDouble(), mergedBox.centerY().toDouble()
                            )
                        )
                    }

                    val cleanD = extractCleanDigits(combinedText)
                    if (cleanD != null) {
                        val mergedBox = Rect(
                            Math.min(leftE.box.left, rightE.box.left),
                            Math.min(leftE.box.top, rightE.box.top),
                            Math.max(leftE.box.right, rightE.box.right),
                            Math.max(leftE.box.bottom, rightE.box.bottom)
                        )
                        digitCandidates.add(
                            TokenCandidate(
                                cleanD, mergedBox,
                                mergedBox.centerX().toDouble(), mergedBox.centerY().toDouble()
                            )
                        )
                    }
                }
            }
        }

        if (letterCandidates.isEmpty() || digitCandidates.isEmpty()) {
            return null
        }

        // (3) 정방향 위치 구조 엄격 검증
        var bestPair: Pair<String, String>? = null
        var bestScore = Double.MAX_VALUE
        var isTooFar = false

        for (lCand in letterCandidates) {
            for (dCand in digitCandidates) {
                val isSameLine = Math.abs(lCand.centerY - dCand.centerY) <= 35
                val isAbove = lCand.centerY <= dCand.centerY - 10
                val isLeftOf = isSameLine && (lCand.centerX <= dCand.centerX + 10)

                // 규칙 1: 영문이 숫자보다 아래에 있으면 (거꾸로 찍힌 오인식) 탈락!
                if (!isAbove && !isLeftOf) {
                    continue
                }

                // 규칙 2: KC/KFI/방염 마크가 감지되었는데 마크가 영문보다 아래쪽에 있으면 (거꾸로 찍힌 상태) 탈락!
                if (markBox != null && markBox.top > lCand.box.bottom + 20) {
                    continue
                }

                // 규칙 3: 영문 4자리와 숫자 5자리는 스티커 내부에 인접해야 하므로 거리가 280px 초과 시 탈락 (배경 명판 문구와 혼선 방지)
                val distance = Math.hypot(lCand.centerX - dCand.centerX, lCand.centerY - dCand.centerY)
                if (distance > 280) {
                    continue
                }

                // 스티커 거리 및 해상도 체크 (너무 멀어서 문자가 뭉개질 정도로 작은 경우)
                if (lCand.box.height() < 12 || dCand.box.height() < 12) {
                    isTooFar = true
                    continue
                }

                var score = distance

                // 마크 아래에 정방향으로 잘 배치된 후보는 최우선 가산점 부여
                if (markBox != null && markBox.top <= lCand.box.top + 10) {
                    score *= 0.1
                }

                if (score < bestScore) {
                    bestScore = score
                    bestPair = lCand.fixedText to dCand.fixedText
                }
            }
        }

        if (bestPair == null && isTooFar) {
            runOnUiThread {
                overlayView.setStatus("🔍 스티커가 멀리 있습니다. 화면을 조금 확대(줌)해주세요", false)
            }
        }

        return bestPair
    }

    data class KfiMatchRow(
        val index: Int,
        val type: String,
        val manufacturer: String,
        val formMaterial: String,
        val inspRcptNo: String,
        val ticWrtsCd: String,
        val detailUrl: String
    )

    data class KfiDetailInfo(
        val type: String,
        val manufacturer: String,
        val formMaterial: String,
        val officialApprovalNo: String,
        val passDate: String
    )

    private fun parseKfiDetailPage(
        letterCode: String,
        numberCode: String,
        inspRcptNo: String,
        ticWrtsCd: String,
        detailUrl: String,
        fallbackType: String,
        fallbackManuf: String,
        fallbackSpec: String
    ): KfiDetailInfo {
        var approvalNo = ""
        var passDate = ""
        var type = fallbackType
        var manuf = fallbackManuf
        var spec = fallbackSpec

        val url = "https://www.kfi.or.kr/portal/openData/openData/opnPsmrk.do"

        try {
            val doc = if (inspRcptNo.isNotBlank()) {
                Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Safari")
                    .timeout(10_000)
                    .data("searchGb", "01")
                    .data("menuNo", "500336")
                    .data("checkPurpose", "009004")
                    .data("prfc", letterCode.lowercase())
                    .data("psmrkNo", numberCode)
                    .data("inspRcptNo", inspRcptNo)
                    .data("ticWrtsCd", ticWrtsCd)
                    .data("viewType", "view")
                    .data("hisMode", "Y")
                    .data("serMode", "Y")
                    .post()
            } else if (detailUrl.isNotBlank()) {
                Jsoup.connect(detailUrl)
                    .userAgent("Mozilla/5.0 (Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Safari")
                    .timeout(10_000)
                    .get()
            } else null

            if (doc != null) {
                val rows = doc.select("tr")
                for (row in rows) {
                    val label = row.select("th, td.lbl, td.th").text().trim()
                    val value = row.select("td").lastOrNull()?.text()?.trim() ?: continue

                    if (label.contains("승인번호") || label.contains("형식승인") || label.contains("성능인증") || label.contains("KFI인증")) {
                        if (value.contains("/")) {
                            val parts = value.split("/")
                            approvalNo = parts[0].trim()
                            if (passDate.isBlank() && parts.size >= 2) {
                                passDate = parts[1].trim()
                            }
                        } else {
                            approvalNo = value
                        }
                    } else if (label.contains("검정일자") || label.contains("합격일자") || label.contains("접수일자")) {
                        if (passDate.isBlank()) {
                            passDate = value
                        }
                    } else if (label.contains("종별")) {
                        if (value.isNotBlank()) type = value
                    } else if (label.contains("제조")) {
                        if (value.isNotBlank()) manuf = value
                    } else if (label.contains("형식명") || label.contains("형식 및 소재") || label.contains("규격")) {
                        if (value.isNotBlank()) spec = value
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("KFI", "Detail page POST parse error", e)
        }

        // KFI 웹상세 파싱 성공 시 무조건 웹상세의 형식승인번호를 그대로 100% 인용 사용!
        if (approvalNo.isNotBlank() && approvalNo != "-") {
            return KfiDetailInfo(type, manuf, spec, approvalNo, passDate)
        }

        // 파싱 실패/비어있는 예외 상황에서만 로컬 DB 비상 백업 사용
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            val fallbackApproval = ApprovalMatcher.findOfficialApprovalNo(db, manuf, type, spec)
            if (fallbackApproval.isNotBlank() && !fallbackApproval.contains("매칭 데이터 없음")) {
                approvalNo = fallbackApproval
            }
        } catch (_: Exception) {}

        if (approvalNo.isBlank()) {
            approvalNo = "공식 KFI 합격등록 완료"
        }

        return KfiDetailInfo(type, manuf, spec, approvalNo, passDate)
    }

    private fun processKfiMatchedItem(
        row: KfiMatchRow,
        letterCode: String,
        numberCode: String
    ) {
        cameraExecutor.execute {
            val detailInfo = parseKfiDetailPage(
                letterCode,
                numberCode,
                row.inspRcptNo,
                row.ticWrtsCd,
                row.detailUrl,
                row.type,
                row.manufacturer,
                row.formMaterial
            )

            val type = detailInfo.type
            val manufacturer = detailInfo.manufacturer
            val formMaterial = detailInfo.formMaterial
            val officialApprovalNo = detailInfo.officialApprovalNo
            val passDate = detailInfo.passDate

            savedLetterCode = letterCode
            savedNumberCode = numberCode

            val db = AppDatabase.getDatabase(applicationContext)
            val cancelList = kotlinx.coroutines.runBlocking { db.cancellationDao().getCancellationsByManufacturer(manufacturer) }
            val matchedCancelEntity = cancelList.firstOrNull { c ->
                val cancelNo = c.approvalNo.replace(Regex("[-\\s]"), "").uppercase()
                val currentNo = officialApprovalNo.replace(Regex("[-\\s]"), "").uppercase()
                cancelNo.isNotBlank() && currentNo.isNotBlank() && (currentNo.contains(cancelNo) || cancelNo.contains(currentNo))
            }

            val isCancelled = matchedCancelEntity != null
            val judgmentHeader = if (isCancelled) {
                "🚨 [종합 판정: 형식승인 취소 제품! (사유: ${matchedCancelEntity.reason})]"
            } else {
                "🟢 [종합 판정: 적합 (정상 인증 소방용품)]"
            }

            val summaryText = buildString {
                appendLine(judgmentHeader)
                appendLine("• 합격 표시: ${letterCode.uppercase()} $numberCode")
                appendLine("• 형식승인번호: $officialApprovalNo")
                if (passDate.isNotBlank()) {
                    appendLine("• 검정합격일자: $passDate")
                }
                appendLine("• 종별: $type")
                appendLine("• 제조업체: $manufacturer")
                appendLine("• 형식 및 소재: $formMaterial")
            }.trim()

            // Room DB에 점검 이력 자동 저장
            try {
                val record = InspectionRecord(
                    formatCode = "${letterCode.uppercase()} $numberCode",
                    type = type,
                    manufacturer = manufacturer,
                    formMaterial = formMaterial,
                    timestamp = System.currentTimeMillis()
                )
                kotlinx.coroutines.runBlocking {
                    db.inspectionDao().insertRecord(record)
                }
            } catch (e: Exception) {
                Log.e("DB", "이력 저장 실패", e)
            }

            val spokenText = if (isCancelled) {
                "위조 또는 취소 경고. 본 제품은 형식승인 취소 이력이 있습니다."
            } else {
                "인증 검수 완료. 정상 인증된 소방용품입니다. 종별은 $type, 제조업체는 $manufacturer 입니다."
            }

            lastSpokenText = spokenText
            if (::tts.isInitialized) {
                tts.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, null)
            }

            runOnUiThread {
                summaryCardTextView.text = summaryText
            }

            updatePricePositionUI(type, formMaterial)
        }
    }

    // ================= KFI 합격표시 조회(웹) =================
    private fun fetchKfiResult(letterCode: String, numberCode: String, isRetry: Boolean = false) {
        cameraExecutor.execute {
            try {
                val url = "https://www.kfi.or.kr/portal/openData/openData/opnPsmrk.do"

                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Safari")
                    .timeout(15_000)
                    .data("searchGb", "01")
                    .data("menuNo", "500336")
                    .data("checkPurpose", "009004")
                    .data("prfc", letterCode.lowercase())
                    .data("psmrkNo", numberCode)
                    .data("serMode", "Y")
                    .data("hisMode", "Y")
                    .data("serPrfc", letterCode.lowercase())
                    .data("serPsmrkNo", numberCode)
                    .get()

                val table = doc.select("table").firstOrNull { t ->
                    val tx = t.text()
                    tx.contains("종별") && tx.contains("제조업체")
                }

                val dataRows = mutableListOf<KfiMatchRow>()
                val trElements = table?.select("tbody tr")
                if (trElements != null) {
                    var idx = 0
                    for (r in trElements) {
                        val tds = r.select("td")
                        if (tds.size >= 3 && !r.text().contains("검색결과가 존재하지 않습니다")) {
                            val rowType = tds.getOrNull(0)?.text()?.trim().orEmpty()
                            val rowManuf = tds.getOrNull(1)?.text()?.trim().orEmpty()
                            val rowSpec = tds.getOrNull(2)?.text()?.trim().orEmpty()

                            val linkElem = tds.getOrNull(0)?.select("a")?.firstOrNull()
                                ?: r.select("a").firstOrNull()
                            var href = linkElem?.attr("abs:href").orEmpty()

                            val rawHtml = r.html() + " " + (linkElem?.outerHtml() ?: "") + " " + r.attr("onclick")
                            val matches = Regex("['\"](\\d{6,8})['\"]").findAll(rawHtml).map { it.groupValues[1] }.toList()

                            val rcptNo = matches.getOrNull(0).orEmpty()
                            val ticCd = matches.getOrNull(1).orEmpty()

                            if (href.isBlank() || href.contains("javascript")) {
                                if (rcptNo.isNotBlank()) {
                                    href = "https://www.kfi.or.kr/portal/openData/openData/opnPsmrk.do?searchGb=01&menuNo=500336&checkPurpose=009004&hisMode=Y&serMode=Y&prfc=${letterCode.lowercase()}&psmrkNo=$numberCode&inspRcptNo=$rcptNo&ticWrtsCd=$ticCd"
                                }
                            }

                            dataRows.add(
                                KfiMatchRow(
                                    index = idx++,
                                    type = rowType,
                                    manufacturer = rowManuf,
                                    formMaterial = rowSpec,
                                    inspRcptNo = rcptNo,
                                    ticWrtsCd = ticCd,
                                    detailUrl = href
                                )
                            )
                        }
                    }
                }

                if (dataRows.isEmpty() && !isRetry) {
                    val swappedLetterCode = letterCode.drop(2) + letterCode.take(2)
                    Log.d("KFI", "Search failed with $letterCode. Retrying with swapped letters: $swappedLetterCode")
                    fetchKfiResult(swappedLetterCode, numberCode, isRetry = true)
                    return@execute
                }

                if (dataRows.isEmpty()) {
                    val summaryText = buildString {
                        appendLine("🔴 [종합 판정: 미등록 제품 또는 불일치]")
                        appendLine("• 합격 표시: ${letterCode.uppercase()} $numberCode")
                        appendLine("• 형식승인번호: -")
                        appendLine("• 합격표시 정보를 조회할 수 없습니다.")
                        appendLine("• (해당 합격표시가 미등록이거나 조회조건 불일치일 수 있습니다.)")
                    }.trim()

                    val spokenText = "해당 합격표시의 정보를 조회할 수 없습니다."
                    lastSpokenText = spokenText
                    if (::tts.isInitialized) {
                        tts.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, null)
                    }

                    runOnUiThread {
                        kfiChipsHeaderTextView.visibility = View.GONE
                        kfiChipsLayout.visibility = View.GONE
                        summaryCardTextView.text = summaryText
                    }
                } else {
                    runOnUiThread {
                        if (dataRows.size > 1) {
                            kfiChipsHeaderTextView.text = "⚠️ 본 합격표시로 ${dataRows.size}개의 KFI 등록 품목이 검색되었습니다. 점검 품목을 선택해 주세요:"
                            kfiChipsHeaderTextView.visibility = View.VISIBLE
                            kfiChipsLayout.visibility = View.VISIBLE
                            kfiChipsLayout.removeAllViews()

                            for (r in dataRows) {
                                val chipButton = Button(this).apply {
                                    text = "🔘 ${r.type} (${r.manufacturer})"
                                    textSize = 14f
                                    setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        bottomMargin = dpToPx(6)
                                    }
                                }
                                chipButton.setOnClickListener {
                                    processKfiMatchedItem(r, letterCode, numberCode)
                                }
                                kfiChipsLayout.addView(chipButton)
                            }
                        } else {
                            kfiChipsHeaderTextView.visibility = View.GONE
                            kfiChipsLayout.visibility = View.GONE
                        }
                    }

                    processKfiMatchedItem(dataRows.first(), letterCode, numberCode)
                }

            } catch (e: Exception) {
                Log.e("KFI", "합격표시 조회 실패", e)
                if (!isRetry) {
                    val swappedLetterCode = letterCode.drop(2) + letterCode.take(2)
                    fetchKfiResult(swappedLetterCode, numberCode, isRetry = true)
                } else {
                    runOnUiThread {
                        summaryCardTextView.text = "• 합격표시 정보를 조회하는 중 오류가 발생했습니다.\n• ${e.message}"
                    }
                }
            }
        }
    }

    // ================= 화면 전환 / 유틸 =================
    private fun showFullImage() {
        val bmp = lastCroppedBitmap ?: return
        val dialog = Dialog(this)
        val imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(imageView)
        dialog.show()
    }

    private fun showComparisonPage() {
        resultContainer.visibility = View.GONE
        comparisonContainer.visibility = View.VISIBLE
    }

    private fun showResultScreenFromComparison() {
        comparisonContainer.visibility = View.GONE
        resultContainer.visibility = View.VISIBLE
    }

    private fun updatePricePositionUI(type: String, formMaterial: String) {
        cameraExecutor.execute {
            val priceResult = ShoppingPriceUtil.fetchMarketPrices(type, formMaterial)
            lastPricePositionResult = priceResult

            runOnUiThread {
                if (priceResult != null) {
                    priceCardLayout.visibility = View.VISIBLE

                    val minStr = String.format("최저 %,d원", priceResult.minPrice)
                    val maxStr = String.format("최고 %,d원", priceResult.maxPrice)
                    val estStr = String.format("평균 %,d원", priceResult.estimatedPrice)

                    linearPriceGraphView.setPriceData(minStr, maxStr, estStr, priceResult.positionPercent)
                    priceBadgeTextView.text = "💰 동급 [$type] 대비 [${priceResult.positionBadge}] 포지션입니다."

                    comparisonTitleTextView.text = "📊 [$type] 동급 유사 제품군 시장 가격 비교"

                    comparisonItemsLayout.removeAllViews()
                    for ((index, prod) in priceResult.products.withIndex()) {
                        val itemCard = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
                            setBackgroundColor(0xFFF9F9F9.toInt())
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = dpToPx(10)
                            }
                        }

                        val titleTv = TextView(this).apply {
                            text = "${index + 1}. ${prod.title}"
                            textSize = 15f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(0xFF333333.toInt())
                        }

                        val infoRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, dpToPx(4), 0, 0)
                        }

                        val priceTv = TextView(this).apply {
                            text = "가격: ${prod.formattedPrice}  |  제조/판매: ${prod.manufacturer}"
                            textSize = 14f
                            setTextColor(0xFF1976D2.toInt())
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val badgeTv = TextView(this).apply {
                            text = prod.badge
                            textSize = 12f
                            setTextColor(0xFFFFFFFF.toInt())
                            val badgeBg = when {
                                prod.isExcellenceCertified -> 0xFFD84315.toInt() // 우수인증 금빛/주황
                                prod.badge == "알뜰형" -> 0xFF2E7D32.toInt()
                                else -> 0xFF1565C0.toInt()
                            }
                            setBackgroundColor(badgeBg)
                            setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
                        }

                        infoRow.addView(priceTv)
                        infoRow.addView(badgeTv)

                        itemCard.addView(titleTv)
                        itemCard.addView(infoRow)

                        if (prod.linkUrl.isNotBlank()) {
                            itemCard.setOnClickListener {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(prod.linkUrl))
                                    startActivity(intent)
                                } catch (_: Exception) {}
                            }
                        }

                        comparisonItemsLayout.addView(itemCard)
                    }

                    // 투명한 품질 안내 문구 명시 (Disclaimer)
                    val disclaimerTv = TextView(this).apply {
                        text = priceResult.disclaimer
                        textSize = 12f
                        setTextColor(0xFF757575.toInt())
                        setPadding(0, dpToPx(16), 0, dpToPx(16))
                    }
                    comparisonItemsLayout.addView(disclaimerTv)
                }
            }
        }
    }

    private fun showCameraScreen() {
        cameraContainer.visibility = View.VISIBLE
        resultContainer.visibility = View.GONE
        comparisonContainer.visibility = View.GONE

        autoScanEnabled = true
        stableHitCount = 0
        lastDetectedCode = null
        startAutoScanLoop()
    }

    private fun showResultScreen() {
        cameraContainer.visibility = View.GONE
        resultContainer.visibility = View.VISIBLE
    }

    private fun speakLastResult() {
        if (lastSpokenText.isNotBlank()) {
            tts.speak(lastSpokenText, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            Toast.makeText(this, "읽어드릴 내용이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        autoHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    // ============== 가이드 박스 오버레이 ==============
    class GuideOverlayView(context: android.content.Context) : View(context) {

        private val boxPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        private val dimPaint = Paint().apply {
            color = 0x66000000.toInt()
        }

        private var boxRect = RectF()
        private var statusMessage = "소방용품 인증 스티커를 박스에 맞춰주세요"
        private var statusBgColor = 0xAA000000.toInt()

        private var touchX = -1f
        private var touchY = -1f

        fun setStatus(msg: String, isSuccess: Boolean) {
            statusMessage = msg
            statusBgColor = if (isSuccess) 0xAA2E7D32.toInt() else 0xAA000000.toInt()
            invalidate()
        }

        fun setTouchPoint(x: Float, y: Float) {
            touchX = x
            touchY = y
            invalidate()
            postDelayed({
                touchX = -1f
                touchY = -1f
                invalidate()
            }, 1200)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)

            val boxWidth = w * 0.7f
            val boxHeight = boxWidth
            val left = (w - boxWidth) / 2f
            val top = (h - boxHeight) / 2f
            boxRect = RectF(left, top, left + boxWidth, top + boxHeight)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

            val clearPaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            val saveCount = canvas.saveLayer(null, null)
            canvas.drawRect(boxRect, clearPaint)
            canvas.restoreToCount(saveCount)

            canvas.drawRect(boxRect, boxPaint)

            if (touchX >= 0f && touchY >= 0f) {
                val ringPaint = Paint().apply {
                    color = Color.YELLOW
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                    isAntiAlias = true
                }
                canvas.drawCircle(touchX, touchY, 70f, ringPaint)
            }

            val badgePaint = Paint().apply {
                color = statusBgColor
                isAntiAlias = true
            }
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 36f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }

            val badgeWidth = boxRect.width() * 0.95f
            val badgeHeight = 85f
            val badgeLeft = boxRect.left + (boxRect.width() - badgeWidth) / 2f
            val badgeTop = boxRect.top - badgeHeight - 24f
            val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + badgeHeight)

            canvas.drawRoundRect(badgeRect, 16f, 16f, badgePaint)

            val textX = badgeRect.centerX()
            val textY = badgeRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2f)
            canvas.drawText(statusMessage, textX, textY, textPaint)
        }

        fun getBoxRect(): RectF = boxRect
    }
}
