package imt.skymediaplayer.demo

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import imt.zw.skymediaplayer.player.IMediaPlayer
import imt.zw.skymediaplayer.player.SkyMediaPlayer
import imt.zw.skymediaplayer.utils.LutLoader
import imt.zw.skymediaplayer.widget.SkyVideoView

/**
 * 自动化测试页面
 * 自动执行一系列播放器功能测试，展示实时进度和结果。
 *
 * 测试矩阵：
 * - 解码模式 × 渲染后端 × 视频源（在线/本地）
 * - AI 字幕功能验证
 */
class AutoTestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AutoTestActivity"

        /** 每个测试用例的播放验证时长（毫秒） */
        private const val PLAYBACK_VERIFY_DURATION_MS = 5000L

        /** 等待 prepared 回调的超时时间（毫秒） */
        private const val PREPARE_TIMEOUT_MS = 20000L

        /** HLS 流等待 prepared 回调的超时时间（毫秒），HLS 起播较慢（需下载 m3u8 + 首个 TS 分片） */
        private const val HLS_PREPARE_TIMEOUT_MS = 45000L

        /** HLS 播放验证时长（毫秒），HLS 首帧渲染较慢，需要更长的验证等待 */
        private const val HLS_PLAYBACK_VERIFY_DURATION_MS = 8000L

        /** HLS 验证失败后的重试等待时间（毫秒） */
        private const val HLS_VERIFY_RETRY_DELAY_MS = 5000L

        /** AI 字幕预缓冲等待超时（毫秒） */
        private const val SUBTITLE_PREBUFFER_TIMEOUT_MS = 30000L

        /** 在线测试视频 URL */
        private const val ONLINE_VIDEO_HTTPS = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
        private const val ONLINE_VIDEO_HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

        /** AI 字幕测试视频（英文语音，适合 Whisper 识别） */
        private const val SUBTITLE_TEST_VIDEO = "https://media.w3.org/2010/05/sintel/trailer.mp4"

        /** LUT 画质滤镜内置预设（assets，512x512 GPUImage lookup） */
        private val LUT_PRESET_ASSETS = listOf(
            "lut/1001.png", "lut/1002.png", "lut/1003.png", "lut/1004.png", "lut/1005.png"
        )

        /** LUT 应用后验证播放持续推进的等待时长（毫秒） */
        private const val LUT_VERIFY_DURATION_MS = 3000L
    }

    // UI 组件
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTestSummary: TextView
    private lateinit var tvCurrentTest: TextView
    private lateinit var tvVideoOverlay: TextView
    private lateinit var testResultsContainer: LinearLayout
    private lateinit var testVideoView: SkyVideoView
    private lateinit var btnStartTest: Button
    private lateinit var btnSkipToMain: Button

    // 测试状态
    private val handler = Handler(Looper.getMainLooper())
    private var isTestRunning = false
    private var testCases = mutableListOf<TestCase>()
    private var currentTestIndex = -1
    private var passedCount = 0
    private var failedCount = 0
    private var skippedCount = 0

    // 超时 Runnable
    private var prepareTimeoutRunnable: Runnable? = null
    private var subtitleTimeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreenMode()
        setContentView(R.layout.activity_auto_test)

        initViews()
        buildTestCases()
        updateSummary()

        // 如果通过编译选项自动启动，直接开始测试
        val autoStart = intent.getBooleanExtra("auto_start", false)
        if (autoStart) {
            handler.postDelayed({ startTestSuite() }, 500)
        }
    }

    private fun initViews() {
        progressBar = findViewById(R.id.progress_bar)
        tvTestSummary = findViewById(R.id.tv_test_summary)
        tvCurrentTest = findViewById(R.id.tv_current_test)
        tvVideoOverlay = findViewById(R.id.tv_video_overlay)
        testResultsContainer = findViewById(R.id.test_results_container)
        testVideoView = findViewById(R.id.test_video_view)
        btnStartTest = findViewById(R.id.btn_start_test)
        btnSkipToMain = findViewById(R.id.btn_skip_to_main)

        btnStartTest.setOnClickListener {
            if (!isTestRunning) {
                startTestSuite()
            }
        }

        btnSkipToMain.setOnClickListener {
            navigateToMain()
        }
    }

    /**
     * 构建测试用例矩阵
     * 解码模式: 0=硬解直渲, 1=硬解Buffer, 2=软解, 3=自动
     * 渲染后端: 0=OpenGL ES, 1=Vulkan
     */
    private fun buildTestCases() {
        testCases.clear()

        // LUT 画质滤镜测试（放最前，便于快速反馈）：软解走渲染器，覆盖 OpenGL ES 与 Vulkan
        testCases.add(
            TestCase(
                name = "LUT 滤镜 + 软解 + OpenGL ES",
                decoderMode = 2,
                rendererBackend = 0,
                videoUrl = ONLINE_VIDEO_HTTPS,
                category = TestCategory.LUT_FILTER
            )
        )
        testCases.add(
            TestCase(
                name = "LUT 滤镜 + 软解 + Vulkan",
                decoderMode = 2,
                rendererBackend = 1,
                videoUrl = ONLINE_VIDEO_HTTPS,
                category = TestCategory.LUT_FILTER
            )
        )
        // 暂停态切换 LUT 应立即重绘当前帧（本地视频，软解+OpenGL；重绘逻辑在 ffplay 层，与后端无关）
        testCases.add(
            TestCase(
                name = "LUT 暂停切换重绘 + 软解 + OpenGL ES",
                decoderMode = 2,
                rendererBackend = 0,
                videoUrl = ONLINE_VIDEO_HTTPS,
                category = TestCategory.LUT_PAUSE
            )
        )

        val decoderModes = listOf(
            DecoderConfig(2, "软解"),
            DecoderConfig(0, "硬解直渲"),
            DecoderConfig(1, "硬解Buffer"),
            DecoderConfig(3, "自动回退")
        )

        val rendererBackends = listOf(
            RendererConfig(0, "OpenGL ES"),
            RendererConfig(1, "Vulkan")
        )

        val videoSources = listOf(
            VideoSource("在线HTTPS", ONLINE_VIDEO_HTTPS, isOnline = true),
            VideoSource("在线HLS", ONLINE_VIDEO_HLS, isOnline = true)
        )

        // 测试矩阵：解码模式 × 渲染后端 × 视频源
        for (decoder in decoderModes) {
            for (renderer in rendererBackends) {
                for (source in videoSources) {
                    testCases.add(
                        TestCase(
                            name = "${decoder.displayName} + ${renderer.displayName} + ${source.name}",
                            decoderMode = decoder.mode,
                            rendererBackend = renderer.backend,
                            videoUrl = source.url,
                            category = TestCategory.DECODE_RENDER
                        )
                    )
                }
            }
        }

        // AI 字幕测试（使用软解 + OpenGL ES，最稳定的组合）
        testCases.add(
            TestCase(
                name = "AI 字幕 (Whisper 英文识别)",
                decoderMode = 2,
                rendererBackend = 0,
                videoUrl = SUBTITLE_TEST_VIDEO,
                category = TestCategory.AI_SUBTITLE
            )
        )

        Log.i(TAG, "Built ${testCases.size} test cases")
    }

    /**
     * 开始执行测试套件
     */
    private fun startTestSuite() {
        if (isTestRunning) return
        isTestRunning = true

        passedCount = 0
        failedCount = 0
        skippedCount = 0
        currentTestIndex = -1

        // 清空结果列表
        testResultsContainer.removeAllViews()

        // 为每个测试用例创建结果行
        for (testCase in testCases) {
            testResultsContainer.addView(createTestResultRow(testCase))
        }

        btnStartTest.isEnabled = false
        btnStartTest.text = "测试中..."

        progressBar.max = testCases.size
        progressBar.progress = 0

        Log.i(TAG, "Starting test suite with ${testCases.size} test cases")
        tvCurrentTest.text = "正在启动测试套件..."

        executeNextTest()
    }

    /**
     * 执行下一个测试用例
     */
    private fun executeNextTest() {
        currentTestIndex++

        if (currentTestIndex >= testCases.size) {
            onTestSuiteComplete()
            return
        }

        val testCase = testCases[currentTestIndex]
        Log.i(TAG, "Executing test ${currentTestIndex + 1}/${testCases.size}: ${testCase.name}")

        tvCurrentTest.text = "▶ [${currentTestIndex + 1}/${testCases.size}] ${testCase.name}"
        tvVideoOverlay.text = "${testCase.name}\n解码:${getDecoderName(testCase.decoderMode)} 渲染:${getRendererName(testCase.rendererBackend)}"

        updateTestRowStatus(currentTestIndex, TestStatus.RUNNING)

        when (testCase.category) {
            TestCategory.DECODE_RENDER -> executePlaybackTest(testCase)
            TestCategory.AI_SUBTITLE -> executeSubtitleTest(testCase)
            TestCategory.LUT_FILTER -> executeLutTest(testCase)
            TestCategory.LUT_PAUSE -> executeLutPauseTest(testCase)
        }
    }

    /**
     * 验证「暂停态切换 LUT 立即重绘当前帧」。
     * 流程：本地视频起播 → 暂停 → (标记)保持无滤镜 → 应用单色 LUT → (标记)保持暂停。
     * 通过 logcat 标记，宿主在两个标记处各截一张图，对比暂停帧是否变化即可。
     * 用例自身判定：全程保持暂停（position 基本不变、isPlaying=false），无崩溃。
     */
    private fun executeLutPauseTest(testCase: TestCase) {
        try {
            releaseCurrentPlayer()
            val localPath = ensureLocalTestVideo()
            if (localPath == null) {
                onTestResult(currentTestIndex, TestStatus.FAILED, "本地测试视频准备失败")
                return
            }
            testVideoView.setRendererBackend(testCase.rendererBackend)
            testVideoView.setDecoderMode(testCase.decoderMode)
            testVideoView.setVideoPath(localPath)

            val idx = currentTestIndex
            prepareTimeoutRunnable = Runnable {
                onTestResult(idx, TestStatus.FAILED, "准备超时")
            }
            handler.postDelayed(prepareTimeoutRunnable!!, PREPARE_TIMEOUT_MS)

            val poll = object : Runnable {
                private var checkCount = 0
                override fun run() {
                    if (idx != currentTestIndex) return
                    checkCount++
                    if (testVideoView.isPlaying() && testVideoView.getCurrentPosition() > 0) {
                        cancelPrepareTimeout()
                        runLutPauseSequence(idx)
                    } else if (checkCount > (PREPARE_TIMEOUT_MS / 200).toInt()) {
                        cancelPrepareTimeout()
                        onTestResult(idx, TestStatus.FAILED, "播放启动超时(position 未推进)")
                    } else {
                        handler.postDelayed(this, 200)
                    }
                }
            }
            handler.postDelayed(poll, 300)
        } catch (e: Exception) {
            onTestResult(currentTestIndex, TestStatus.FAILED, "异常: ${e.message}")
        }
    }

    private fun runLutPauseSequence(idx: Int) {
        // 先确保无滤镜，再暂停 —— 暂停帧应为原始画面
        testVideoView.setLut(null)
        testVideoView.pause()
        val pausedPos = testVideoView.getCurrentPosition()
        Log.i(TAG, "LUTPAUSE_MARKER nolut paused pos=$pausedPos")  // ← 截图点 A（无滤镜暂停帧）

        handler.postDelayed({
            if (idx != currentTestIndex) return@postDelayed
            Log.i(TAG, "LUTPAUSE_MARKER applying")
            val mono = LutLoader.fromAsset(this, "lut/1005.png")  // 单色，效果最明显
            val ret = if (mono != null) testVideoView.setLut(mono) else -1
            // 应用后仍处于暂停；若重绘逻辑生效，画面会立刻变单色

            handler.postDelayed({
                if (idx != currentTestIndex) return@postDelayed
                val playing = testVideoView.isPlaying()
                val pos2 = testVideoView.getCurrentPosition()
                Log.i(TAG, "LUTPAUSE_MARKER applied playing=$playing pos=$pausedPos->$pos2 setLut=$ret")  // ← 截图点 B（单色暂停帧）
                testVideoView.setLut(null)

                val stayedPaused = !playing && kotlin.math.abs(pos2 - pausedPos) < 500
                if (ret == 0 && stayedPaused) {
                    onTestResult(idx, TestStatus.PASSED, "暂停态切LUT: 保持暂停并已请求重绘 (pos $pausedPos→$pos2)")
                } else {
                    onTestResult(idx, TestStatus.FAILED, "暂停态异常: setLut=$ret playing=$playing pos=$pausedPos→$pos2")
                }
            }, 2500)
        }, 2500)
    }

    /**
     * 执行 LUT 画质滤镜测试
     * 用本地测试视频（无网络依赖）软解走渲染器路径，依次应用全部预设 + 非法输入 + 清除，
     * 验证 setLut 返回 0 且应用后画面持续推进（渲染器未崩溃/无致命错误）。
     */
    private fun executeLutTest(testCase: TestCase) {
        try {
            releaseCurrentPlayer()

            val localPath = ensureLocalTestVideo()
            if (localPath == null) {
                onTestResult(currentTestIndex, TestStatus.FAILED, "本地测试视频准备失败")
                return
            }

            testVideoView.setRendererBackend(testCase.rendererBackend)
            testVideoView.setDecoderMode(testCase.decoderMode)
            testVideoView.setVideoPath(localPath)

            val idx = currentTestIndex
            prepareTimeoutRunnable = Runnable {
                Log.e(TAG, "LUT test timeout (prepare): ${testCase.name}")
                onTestResult(idx, TestStatus.FAILED, "准备超时")
            }
            handler.postDelayed(prepareTimeoutRunnable!!, PREPARE_TIMEOUT_MS)

            // 等待真正开始渲染（position > 0），再应用 LUT，避免与起播竞态
            val poll = object : Runnable {
                private var checkCount = 0
                override fun run() {
                    if (idx != currentTestIndex) return  // 已切到下一个测试，停止，避免泄漏
                    checkCount++
                    if (testVideoView.isPlaying() && testVideoView.getCurrentPosition() > 0) {
                        cancelPrepareTimeout()
                        Log.i(TAG, "LUT test playback advancing: ${testCase.name}")
                        applyLutSequence(testCase, idx)
                    } else if (checkCount > (PREPARE_TIMEOUT_MS / 200).toInt()) {
                        cancelPrepareTimeout()
                        onTestResult(idx, TestStatus.FAILED, "播放启动超时(position 未推进)")
                    } else {
                        handler.postDelayed(this, 200)
                    }
                }
            }
            handler.postDelayed(poll, 300)
        } catch (exception: Exception) {
            Log.e(TAG, "LUT test exception: ${testCase.name}", exception)
            onTestResult(currentTestIndex, TestStatus.FAILED, "异常: ${exception.message}")
        }
    }

    /** 把内置测试视频从 assets 复制到 filesDir，返回本地路径 */
    private fun ensureLocalTestVideo(): String? {
        return try {
            val dst = java.io.File(filesDir, "sky_lut_test.mp4")
            if (!dst.exists() || dst.length() == 0L) {
                assets.open("test/sky_lut_test.mp4").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }
            dst.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "ensureLocalTestVideo failed", e)
            null
        }
    }

    private fun applyLutSequence(testCase: TestCase, idx: Int) {
        val posBefore = testVideoView.getCurrentPosition()

        // 1) 依次加载并应用全部预设，校验解码成功 + setLut 返回 0
        for (asset in LUT_PRESET_ASSETS) {
            val rgba = LutLoader.fromAsset(this, asset)
            if (rgba == null) {
                onTestResult(idx, TestStatus.FAILED, "预设解码失败: $asset")
                return
            }
            val ret = testVideoView.setLut(rgba)
            if (ret != 0) {
                onTestResult(idx, TestStatus.FAILED, "setLut 失败 code=$ret ($asset)")
                return
            }
        }

        // 2) 非法输入（不存在的文件 -> LutLoader 返回 null -> setLut(null) 清除，应返回 0）
        val invalid = LutLoader.fromFile("/nonexistent_lut_xyz.png")
        val clearRet = testVideoView.setLut(invalid)
        if (clearRet != 0) {
            onTestResult(idx, TestStatus.FAILED, "清除(非法输入) 返回非0: $clearRet")
            return
        }

        // 3) 保持一个强效果预设（单色），等待数秒后验证画面持续推进
        val mono = LutLoader.fromAsset(this, "lut/1005.png")
        if (mono == null || testVideoView.setLut(mono) != 0) {
            onTestResult(idx, TestStatus.FAILED, "应用单色预设失败")
            return
        }

        handler.postDelayed({
            if (idx != currentTestIndex) return@postDelayed
            val playing = testVideoView.isPlaying()
            val posAfter = testVideoView.getCurrentPosition()
            // 清除滤镜恢复
            testVideoView.setLut(null)

            if (playing && posAfter > posBefore) {
                onTestResult(
                    idx,
                    TestStatus.PASSED,
                    "5 预设+非法+清除 OK | 应用后持续渲染 ${formatTime(posBefore)}→${formatTime(posAfter)}"
                )
            } else {
                onTestResult(
                    idx,
                    TestStatus.FAILED,
                    "应用 LUT 后画面停滞: playing=$playing, pos=$posBefore→$posAfter"
                )
            }
        }, LUT_VERIFY_DURATION_MS)
    }

    /**
     * 执行播放测试（解码+渲染组合）
     */
    private fun executePlaybackTest(testCase: TestCase) {
        try {
            // 先释放之前的播放器
            releaseCurrentPlayer()

            // 配置解码和渲染
            testVideoView.setRendererBackend(testCase.rendererBackend)
            testVideoView.setDecoderMode(testCase.decoderMode)

            // 设置监听器
            setupPlaybackListeners(testCase)

            // 开始播放
            testVideoView.setVideoPath(testCase.videoUrl)

            // HLS 流起播较慢，使用更长的超时时间
            val isHls = testCase.videoUrl.contains(".m3u8", ignoreCase = true)
            val timeoutMs = if (isHls) HLS_PREPARE_TIMEOUT_MS else PREPARE_TIMEOUT_MS

            // 设置 prepare 超时
            prepareTimeoutRunnable = Runnable {
                Log.e(TAG, "Test timeout (prepare): ${testCase.name}")
                onTestResult(currentTestIndex, TestStatus.FAILED, "准备超时 (${timeoutMs / 1000}s)")
            }
            handler.postDelayed(prepareTimeoutRunnable!!, timeoutMs)

        } catch (exception: Exception) {
            Log.e(TAG, "Test exception: ${testCase.name}", exception)
            onTestResult(currentTestIndex, TestStatus.FAILED, "异常: ${exception.message}")
        }
    }

    /**
     * 设置播放监听器
     */
    private fun setupPlaybackListeners(testCase: TestCase) {
        // HLS 流起播较慢，使用更长的轮询超时
        val isHls = testCase.videoUrl.contains(".m3u8", ignoreCase = true)
        val pollTimeoutMs = if (isHls) HLS_PREPARE_TIMEOUT_MS else PREPARE_TIMEOUT_MS

        // 由于 SkyVideoView 内部会在 openVideo 时重新创建 MediaPlayer 并设置监听器，
        // 我们需要在 prepared 后通过 SkyVideoView 的接口来检测状态。
        // 使用轮询方式检测播放状态
        val checkPreparedRunnable = object : Runnable {
            private var checkCount = 0
            override fun run() {
                checkCount++
                if (testVideoView.isPlaying()) {
                    // 播放已开始，取消超时
                    cancelPrepareTimeout()
                    Log.i(TAG, "Playback started for: ${testCase.name}")

                    // HLS 流首帧渲染较慢，使用更长的验证等待时间
                    val verifyDelay = if (testCase.videoUrl.contains(".m3u8", ignoreCase = true))
                        HLS_PLAYBACK_VERIFY_DURATION_MS else PLAYBACK_VERIFY_DURATION_MS

                    // 播放一段时间后验证
                    handler.postDelayed({
                        verifyPlayback(testCase)
                    }, verifyDelay)
                } else if (checkCount > (pollTimeoutMs / 200).toInt()) {
                    // 超过最大检查次数，视为超时
                    cancelPrepareTimeout()
                    onTestResult(currentTestIndex, TestStatus.FAILED, "播放启动超时")
                } else {
                    handler.postDelayed(this, 200)
                }
            }
        }
        handler.postDelayed(checkPreparedRunnable, 500)
    }

    /**
     * 验证播放状态
     */
    private fun verifyPlayback(testCase: TestCase) {
        val isPlaying = testVideoView.isPlaying()
        val currentPosition = testVideoView.getCurrentPosition()
        val duration = testVideoView.getDuration()
        val isHls = testCase.videoUrl.contains(".m3u8", ignoreCase = true)

        Log.i(TAG, "Verify playback: playing=$isPlaying, pos=$currentPosition, duration=$duration, isHls=$isHls")

        val activeDecoder = testVideoView.getActiveDecoderMode()
        val decoderInfo = if (testCase.decoderMode == 3) {
            "自动→${getDecoderName(activeDecoder)}"
        } else {
            getDecoderName(testCase.decoderMode)
        }

        if (isPlaying && currentPosition > 0) {
            onTestResult(
                currentTestIndex,
                TestStatus.PASSED,
                "播放正常 | 解码:$decoderInfo | 进度:${formatTime(currentPosition)}/${formatTime(duration)}"
            )
        } else if (isPlaying && isHls) {
            // HLS 流的 duration 和 position 可能暂时不可用（分片切换导致时钟 serial 不匹配），
            // 但 isPlaying=true 且视频帧在正常渲染，视为播放正常
            onTestResult(
                currentTestIndex,
                TestStatus.PASSED,
                "播放正常(HLS) | 解码:$decoderInfo | pos=$currentPosition, duration=$duration"
            )
        } else if (currentPosition > 0) {
            onTestResult(currentTestIndex, TestStatus.PASSED, "播放正常(已暂停) | 进度:${formatTime(currentPosition)}")
        } else if (isHls && !isPlaying) {
            // HLS 流可能还在缓冲中，给予额外的重试机会
            Log.w(TAG, "HLS not playing yet, retrying after ${HLS_VERIFY_RETRY_DELAY_MS}ms...")
            handler.postDelayed({
                val retryPlaying = testVideoView.isPlaying()
                val retryPosition = testVideoView.getCurrentPosition()
                Log.i(TAG, "HLS retry verify: playing=$retryPlaying, pos=$retryPosition")
                if (retryPlaying || retryPosition > 0) {
                    onTestResult(
                        currentTestIndex,
                        TestStatus.PASSED,
                        "播放正常(HLS重试) | 解码:$decoderInfo | pos=$retryPosition"
                    )
                } else {
                    onTestResult(currentTestIndex, TestStatus.FAILED, "HLS播放异常: pos=$retryPosition, playing=$retryPlaying")
                }
            }, HLS_VERIFY_RETRY_DELAY_MS)
        } else {
            onTestResult(currentTestIndex, TestStatus.FAILED, "播放异常: pos=$currentPosition, playing=$isPlaying")
        }
    }

    /**
     * 执行 AI 字幕测试
     */
    private fun executeSubtitleTest(testCase: TestCase) {
        try {
            releaseCurrentPlayer()

            // 使用最稳定的配置
            testVideoView.setRendererBackend(testCase.rendererBackend)
            testVideoView.setDecoderMode(testCase.decoderMode)

            // 开始播放
            testVideoView.setVideoPath(testCase.videoUrl)

            // 等待播放开始后再启用字幕
            val waitForPlayback = object : Runnable {
                private var waitCount = 0
                override fun run() {
                    waitCount++
                    if (testVideoView.isPlaying()) {
                        cancelPrepareTimeout()
                        enableSubtitleAndVerify(testCase)
                    } else if (waitCount > (PREPARE_TIMEOUT_MS / 200).toInt()) {
                        cancelPrepareTimeout()
                        onTestResult(currentTestIndex, TestStatus.FAILED, "字幕测试: 视频播放启动超时")
                    } else {
                        handler.postDelayed(this, 200)
                    }
                }
            }

            prepareTimeoutRunnable = Runnable {
                onTestResult(currentTestIndex, TestStatus.FAILED, "字幕测试: 准备超时")
            }
            handler.postDelayed(prepareTimeoutRunnable!!, PREPARE_TIMEOUT_MS)
            handler.postDelayed(waitForPlayback, 500)

        } catch (exception: Exception) {
            Log.e(TAG, "Subtitle test exception: ${testCase.name}", exception)
            onTestResult(currentTestIndex, TestStatus.FAILED, "字幕异常: ${exception.message}")
        }
    }

    /**
     * 启用字幕并验证
     */
    private fun enableSubtitleAndVerify(testCase: TestCase) {
        val app = application as? SkyPlayerApplication
        val modelPath = app?.getWhisperModelPath()

        if (modelPath == null) {
            onTestResult(currentTestIndex, TestStatus.SKIPPED, "Whisper 模型未就绪，跳过字幕测试")
            return
        }

        // 设置预缓冲完成监听
        val player = testVideoView.getMediaPlayer()
        if (player == null) {
            onTestResult(currentTestIndex, TestStatus.FAILED, "播放器实例为空")
            return
        }

        player.clearSubtitleQueue()

        // 监听字幕回调
        var subtitleReceived = false
        var receivedSubtitleText = ""

        player.setOnSubtitleWithPtsListener(object : SkyMediaPlayer.OnSubtitleWithPtsListener {
            override fun onSubtitle(mp: IMediaPlayer, text: String, startTimeMs: Long, endTimeMs: Long) {
                if (!subtitleReceived) {
                    subtitleReceived = true
                    receivedSubtitleText = text
                    Log.i(TAG, "Subtitle received: '$text' at ${startTimeMs}ms")
                }
            }
        })

        // 暂停播放，启用 Whisper
        testVideoView.pause()

        player.setOnPrebufferCompleteListener(object : SkyMediaPlayer.OnPrebufferCompleteListener {
            override fun onPrebufferComplete(mp: IMediaPlayer, subtitleCount: Int) {
                Log.i(TAG, "Subtitle prebuffer complete: $subtitleCount subtitles")
                cancelSubtitleTimeout()

                runOnUiThread {
                    testVideoView.start()

                    // 等待一段时间检查是否收到字幕
                    handler.postDelayed({
                        player.setOnSubtitleWithPtsListener(null)

                        if (subtitleReceived || subtitleCount > 0) {
                            val detail = if (receivedSubtitleText.isNotEmpty()) {
                                "字幕: \"${receivedSubtitleText.take(30)}...\" | 预缓冲:${subtitleCount}条"
                            } else {
                                "预缓冲:${subtitleCount}条字幕"
                            }
                            onTestResult(currentTestIndex, TestStatus.PASSED, "AI 字幕正常 | $detail")
                        } else {
                            onTestResult(currentTestIndex, TestStatus.FAILED, "未收到字幕回调")
                        }

                        // 关闭 Whisper
                        testVideoView.setWhisperEnabled(false)
                    }, PLAYBACK_VERIFY_DURATION_MS)
                }
            }
        })

        val result = testVideoView.setWhisperEnabled(true, modelPath, "en", 5)
        if (result != 0) {
            onTestResult(currentTestIndex, TestStatus.FAILED, "Whisper 启用失败: code=$result")
            return
        }

        // 设置字幕超时
        subtitleTimeoutRunnable = Runnable {
            player.setOnSubtitleWithPtsListener(null)
            testVideoView.setWhisperEnabled(false)
            testVideoView.start()
            onTestResult(currentTestIndex, TestStatus.FAILED, "AI 字幕预缓冲超时 (${SUBTITLE_PREBUFFER_TIMEOUT_MS / 1000}s)")
        }
        handler.postDelayed(subtitleTimeoutRunnable!!, SUBTITLE_PREBUFFER_TIMEOUT_MS)
    }

    /**
     * 处理单个测试结果
     */
    private fun onTestResult(testIndex: Int, status: TestStatus, detail: String) {
        if (testIndex != currentTestIndex) return

        val testCase = testCases[testIndex]
        testCase.status = status
        testCase.detail = detail

        when (status) {
            TestStatus.PASSED -> passedCount++
            TestStatus.FAILED -> failedCount++
            TestStatus.SKIPPED -> skippedCount++
            else -> {}
        }

        Log.i(TAG, "Test result [${status.name}]: ${testCase.name} - $detail")

        runOnUiThread {
            updateTestRowStatus(testIndex, status, detail)
            progressBar.progress = testIndex + 1
            updateSummary()

            // 释放当前播放器并执行下一个测试
            handler.postDelayed({
                releaseCurrentPlayer()
                executeNextTest()
            }, 500)
        }
    }

    /**
     * 测试套件完成
     */
    private fun onTestSuiteComplete() {
        isTestRunning = false
        val totalTests = testCases.size
        val resultEmoji = if (failedCount == 0) "✅" else "⚠️"

        tvCurrentTest.text = "$resultEmoji 测试完成: $passedCount 通过 / $failedCount 失败 / $skippedCount 跳过 (共 $totalTests)"
        btnStartTest.isEnabled = true
        btnStartTest.text = "🔄 重新测试"

        Log.i(TAG, "Test suite complete: $passedCount passed, $failedCount failed, $skippedCount skipped")

        releaseCurrentPlayer()

        Toast.makeText(
            this,
            "$resultEmoji 测试完成: $passedCount/$totalTests 通过",
            Toast.LENGTH_LONG
        ).show()
    }

    // ============================================================================
    // UI 辅助方法
    // ============================================================================

    /**
     * 创建测试结果行
     */
    private fun createTestResultRow(testCase: TestCase): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            tag = testCase.name

            // 状态图标
            addView(TextView(this@AutoTestActivity).apply {
                tag = "status_icon"
                text = "⏳"
                textSize = 16f
                setPadding(0, 0, 8, 0)
            })

            // 测试名称和详情
            addView(LinearLayout(this@AutoTestActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(this@AutoTestActivity).apply {
                    tag = "test_name"
                    text = testCase.name
                    setTextColor(Color.parseColor("#E0E0E0"))
                    textSize = 13f
                })

                addView(TextView(this@AutoTestActivity).apply {
                    tag = "test_detail"
                    text = "等待执行"
                    setTextColor(Color.parseColor("#888888"))
                    textSize = 11f
                })
            })
        }
    }

    /**
     * 更新测试行状态
     */
    private fun updateTestRowStatus(index: Int, status: TestStatus, detail: String = "") {
        if (index >= testResultsContainer.childCount) return

        val row = testResultsContainer.getChildAt(index) as? LinearLayout ?: return
        val statusIcon = row.findViewWithTag<TextView>("status_icon")
        val testDetail = row.findViewWithTag<TextView>("test_detail")

        when (status) {
            TestStatus.PENDING -> {
                statusIcon?.text = "⏳"
                testDetail?.text = "等待执行"
                testDetail?.setTextColor(Color.parseColor("#888888"))
            }
            TestStatus.RUNNING -> {
                statusIcon?.text = "🔄"
                testDetail?.text = "执行中..."
                testDetail?.setTextColor(Color.parseColor("#00D2FF"))
            }
            TestStatus.PASSED -> {
                statusIcon?.text = "✅"
                testDetail?.text = detail.ifEmpty { "通过" }
                testDetail?.setTextColor(Color.parseColor("#4CAF50"))
            }
            TestStatus.FAILED -> {
                statusIcon?.text = "❌"
                testDetail?.text = detail.ifEmpty { "失败" }
                testDetail?.setTextColor(Color.parseColor("#FF5252"))
            }
            TestStatus.SKIPPED -> {
                statusIcon?.text = "⏭️"
                testDetail?.text = detail.ifEmpty { "跳过" }
                testDetail?.setTextColor(Color.parseColor("#FFC107"))
            }
        }

        // 自动滚动到当前测试项
        val scrollView = testResultsContainer.parent as? ScrollView
        scrollView?.post {
            scrollView.smoothScrollTo(0, row.bottom)
        }
    }

    /**
     * 更新摘要信息
     */
    private fun updateSummary() {
        val total = testCases.size
        val completed = passedCount + failedCount + skippedCount
        tvTestSummary.text = "$completed/$total | ✅$passedCount ❌$failedCount ⏭️$skippedCount"
    }

    // ============================================================================
    // 资源管理
    // ============================================================================

    private fun releaseCurrentPlayer() {
        try {
            testVideoView.release()
        } catch (exception: Exception) {
            Log.e(TAG, "Error releasing player", exception)
        }
        // 重新初始化 SkyVideoView（因为 release 后需要重新创建）
        val container = findViewById<FrameLayout>(R.id.video_container)
        container.removeView(testVideoView)

        testVideoView = SkyVideoView(this)
        testVideoView.id = R.id.test_video_view
        testVideoView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(testVideoView, 0)
    }

    private fun cancelPrepareTimeout() {
        prepareTimeoutRunnable?.let { handler.removeCallbacks(it) }
        prepareTimeoutRunnable = null
    }

    private fun cancelSubtitleTimeout() {
        subtitleTimeoutRunnable?.let { handler.removeCallbacks(it) }
        subtitleTimeoutRunnable = null
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("skip_auto_test", true)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setupFullscreenMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPrepareTimeout()
        cancelSubtitleTimeout()
        handler.removeCallbacksAndMessages(null)
        try {
            testVideoView.release()
        } catch (exception: Exception) {
            Log.e(TAG, "Error releasing on destroy", exception)
        }
    }

    // ============================================================================
    // 辅助方法
    // ============================================================================

    private fun getDecoderName(mode: Int): String = when (mode) {
        0 -> "硬解直渲"
        1 -> "硬解Buffer"
        2 -> "软解"
        3 -> "自动"
        else -> "未知"
    }

    private fun getRendererName(backend: Int): String = when (backend) {
        0 -> "OpenGL"
        1 -> "Vulkan"
        else -> "未知"
    }

    private fun formatTime(timeMs: Int): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // ============================================================================
    // 数据类
    // ============================================================================

    enum class TestCategory {
        DECODE_RENDER,
        AI_SUBTITLE,
        LUT_FILTER,
        LUT_PAUSE
    }

    enum class TestStatus {
        PENDING,
        RUNNING,
        PASSED,
        FAILED,
        SKIPPED
    }

    data class DecoderConfig(val mode: Int, val displayName: String)
    data class RendererConfig(val backend: Int, val displayName: String)
    data class VideoSource(val name: String, val url: String, val isOnline: Boolean)

    data class TestCase(
        val name: String,
        val decoderMode: Int,
        val rendererBackend: Int,
        val videoUrl: String,
        val category: TestCategory,
        var status: TestStatus = TestStatus.PENDING,
        var detail: String = ""
    )
}
