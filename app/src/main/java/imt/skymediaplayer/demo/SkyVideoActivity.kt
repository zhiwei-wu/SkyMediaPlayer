package imt.skymediaplayer.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import imt.zw.skymediaplayer.player.IMediaPlayer
import imt.zw.skymediaplayer.player.SkyMediaPlayer
import imt.zw.skymediaplayer.utils.LutLoader
import imt.zw.skymediaplayer.widget.SkyVideoView
import imt.zw.skymediaplayer.widget.control.SkyQualityPanel
import imt.zw.skymediaplayer.widget.control.OnSubtitleSettingsChangeListener
import imt.zw.skymediaplayer.widget.control.SubtitleSettings

class SkyVideoActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SkyVideoActivity"

        // 内置画质滤镜预设（id 即 assets 文件名；来自 QQLive lutCache，512x512 GPUImage lookup）
        private val LUT_PRESETS = listOf(
            "1001" to "增强",
            "1002" to "清新",
            "1003" to "鲜艳",
            "1004" to "质感",
            "1005" to "单色",
        )
        private const val FILTER_NONE = "none"
        private const val FILTER_DEFAULT = "default"
    }

    // 当前应用的 LUT 数据与强度（强度滑动时复用同一份数据重新应用）
    private var currentLutRgba: ByteArray? = null
    private var currentLutIntensity: Int = 100

    // 当前画质增强参数（各 0-100，0=关闭）
    private var currentEnhanceSharpness: Int = 0
    private var currentEnhanceDeband: Int = 0

    private lateinit var mSkyVideoView: SkyVideoView
    private lateinit var emptyMenu: LinearLayout
    private lateinit var topMenu: LinearLayout
    private var hasVideoSource = false
    // 打开 SAF 前保存的方向，返回后恢复
    private var orientationBeforePicker: Int = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    private var wasPlayingBeforePicker = false
    private var currentSource: String? = null
    private var wasPlayingBeforePause = false

    // 本地文件选择：OpenDocument 持久化授权，最近播放重启后仍可读
    private val localPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // 从 SAF 返回：恢复进入前的视频方向
        requestedOrientation = orientationBeforePicker
        // 未选 或 选了相同视频：恢复播放原视频
        if (uri == null || uri.toString() == currentSource) {
            if (wasPlayingBeforePicker) mSkyVideoView.start()
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "takePersistableUriPermission failed: ${e.message}")
        }
        RecentPlayPreferences.add(this, uri.toString(), queryDisplayName(uri), RecentPlayPreferences.TYPE_LOCAL)
        // 方向恢复会重建 Surface，延后到 Surface 就绪再起播，避免新片不开
        mSkyVideoView.post { playUri(uri) }
    }

    // 选择画质滤镜文件（512x512 PNG），拷贝到 app 私有目录并持久化路径
    private val lutPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val dst = java.io.File(filesDir, "lut_default.png")
            contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            FilterPreferences.setFilterFilePath(this, dst.absolutePath)
            Toast.makeText(this, "已设置滤镜文件，播放器选「默认」即可应用", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存滤镜文件失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 预缓冲 UI 组件
    private var prebufferOverlay: FrameLayout? = null
    private var prebufferProgress: ProgressBar? = null
    private var prebufferText: TextView? = null

    // 字幕同步状态
    private var isSubtitleSyncEnabled = false

    // 当前显示的字幕
    private var currentSubtitleText: String? = null

    // 上一次的字幕启用状态（用于判断是否需要触发开启/关闭操作）
    private var lastSubtitleEnabled: Boolean = false

    // 字幕调试模式开关
    private var isSubtitleDebugMode = false  // 默认关闭调试模式

    // 字幕处理间隔（秒）
    private var subtitleProcessingInterval = SubtitleSettings.DEFAULT_PROCESSING_INTERVAL

    // Seek 后字幕加载状态
    private var isWaitingForSubtitleAfterSeek = false

    // 字幕等待队列和 Handler
    private val subtitleHandler = Handler(Looper.getMainLooper())
    
    // 待展示的字幕队列（按 startTimeMs 排序）
    private data class PendingSubtitle(
        val text: String,
        val startTimeMs: Long,
        val endTimeMs: Long
    )
    private val pendingSubtitleQueue = mutableListOf<PendingSubtitle>()
    private var subtitleCheckRunnable: Runnable? = null
    private val SUBTITLE_CHECK_INTERVAL_MS = 100L  // 每 100ms 检查一次字幕队列

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 自动化测试模式：编译选项控制，启用时直接跳转到测试页面
        val skipAutoTest = intent.getBooleanExtra("skip_auto_test", false)
        if (BuildConfig.AUTO_TEST_ENABLED && !skipAutoTest) {
            val testIntent = Intent(this, AutoTestActivity::class.java)
            testIntent.putExtra("auto_start", true)
            startActivity(testIntent)
            finish()
            return
        }

        // 设置全沉浸式全屏
        setupFullscreenMode()

        setContentView(R.layout.activity_layout)

        // 初始化视频播放器
        mSkyVideoView = findViewById(R.id.sky_video_view)

        // 渲染后端 + 解码模式从持久化设置读取（不再经配置页 Intent 传入）
        val rendererBackend = RendererPreferences.getRendererBackend(this)
        mSkyVideoView.setRendererBackend(rendererBackend)
        Log.i(TAG, "Using renderer backend: $rendererBackend")

        val decoderMode = DecoderPreferences.getDecoderMode(this)
        mSkyVideoView.setDecoderMode(decoderMode)
        Log.i(TAG, "Using decoder mode: $decoderMode")

        // 空态选片菜单（首次进入居中）
        emptyMenu = findViewById(R.id.empty_menu)
        findViewById<Button>(R.id.btn_pick_local).setOnClickListener { openLocalPicker() }
        findViewById<Button>(R.id.btn_pick_url).setOnClickListener { showUrlInputDialog() }

        // 起播后顶部切换菜单（跟随播控显隐）
        topMenu = findViewById(R.id.top_menu)
        findViewById<Button>(R.id.top_local).setOnClickListener { openLocalPicker() }
        findViewById<Button>(R.id.top_online).setOnClickListener { showUrlInputDialog() }
        findViewById<Button>(R.id.top_recent).setOnClickListener { showRecentDialog() }
        findViewById<Button>(R.id.top_settings).setOnClickListener { showRenderSettingsDialog() }
        mSkyVideoView.setOnControlVisibilityChangeListener { visible ->
            topMenu.visibility = if (visible && hasVideoSource) View.VISIBLE else View.GONE
        }

        // 设置旋转按钮监听
        setupRotateButton()

        // 顶部「退出」按钮：退出播放器
        mSkyVideoView.setOnBackButtonClickListener { finish() }

        // 设置调试信息按钮监听
        setupDebugButton()

        // 设置画质滤镜按钮监听
        setupFilterButton()

        // 设置 AI 字幕设置变更监听
        setupSubtitleSettingsListener()

        // 设置 Seek 完成监听（用于字幕同步）
        setupSeekCompleteListener()

        // 创建预缓冲 UI
        createPrebufferUI()

        // 无视频源：显示空态选片菜单，等待用户选择
        showEmptyMenu()
    }

    /** 显示空态选片菜单（无视频时）：本地/在线 + 直接列出最近 3 个 */
    private fun showEmptyMenu() {
        hasVideoSource = false
        topMenu.visibility = View.GONE
        emptyMenu.visibility = View.VISIBLE

        val container = findViewById<LinearLayout>(R.id.recent_container)
        container.removeAllViews()
        val recent = RecentPlayPreferences.getAll(this).take(3)
        findViewById<TextView>(R.id.recent_label).visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        recent.forEach { item ->
            container.addView(Button(this).apply {
                text = item.title
                setOnClickListener { playRecent(item) }
            })
        }
    }

    /** 打开本地 SAF：方向交给系统，仅记录进入前方向，返回时恢复 */
    private fun openLocalPicker() {
        ensureAllFilesAccess()
        // 离开播放前暂停当前视频，返回未换片时恢复
        wasPlayingBeforePicker = hasVideoSource && mSkyVideoView.isPlaying()
        if (wasPlayingBeforePicker) mSkyVideoView.pause()
        orientationBeforePicker = requestedOrientation
        localPickerLauncher.launch(arrayOf("video/*"))
    }

    /** 原始路径直开需「所有文件」权限，未授时引导前往设置 */
    private fun ensureAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()) {
            Toast.makeText(this, "请开启「所有文件访问」权限以播放本地视频", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        }
    }

    /** 起播本地 content uri */
    private fun playUri(uri: Uri) {
        hasVideoSource = true
        currentSource = uri.toString()
        emptyMenu.visibility = View.GONE
        Log.d(TAG, "Playing local video URI: $uri")
        applyRenderSettings()
        mSkyVideoView.setVideoURI(uri)  // 备好后由 onPrepared 自动起播
        reapplyQuality()
    }

    /** 起播在线 URL */
    private fun playUrl(url: String) {
        hasVideoSource = true
        currentSource = url
        emptyMenu.visibility = View.GONE
        Log.d(TAG, "Playing online video: $url")
        applyRenderSettings()
        mSkyVideoView.setVideoPath(url)  // 备好后由 onPrepared 自动起播
        reapplyQuality()
        Toast.makeText(this, "正在连接服务器...", Toast.LENGTH_SHORT).show()
    }

    /** 起播新视频前回灌渲染设置：openVideo 重建播放器时即取最新后端/解码，无需退出重进 */
    private fun applyRenderSettings() {
        mSkyVideoView.setRendererBackend(RendererPreferences.getRendererBackend(this))
        mSkyVideoView.setDecoderMode(DecoderPreferences.getDecoderMode(this))
    }

    /** 切换视频后继承上一次的滤镜/增强设置 */
    private fun reapplyQuality() {
        mSkyVideoView.setEnhance(currentEnhanceSharpness / 100f, currentEnhanceDeband / 100f)
        currentLutRgba?.let { mSkyVideoView.setLut(it, currentLutIntensity / 100f) }
    }

    /** 起播最近播放项（本地校验持久授权） */
    private fun playRecent(item: RecentPlayPreferences.Item) {
        if (item.type == RecentPlayPreferences.TYPE_URL) {
            playUrl(item.uri)
        } else {
            ensureAllFilesAccess()
            playUri(Uri.parse(item.uri))
        }
    }

    /** 输入视频 URL 对话框 */
    private fun showUrlInputDialog() {
        val input = EditText(this).apply {
            hint = "输入视频URL (http:// 或 https://)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("视频 URL")
            .setView(input)
            .setPositiveButton("播放") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, "URL必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                } else {
                    RecentPlayPreferences.add(this, url, url.substringAfterLast('/'), RecentPlayPreferences.TYPE_URL)
                    playUrl(url)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 最近播放列表 */
    private fun showRecentDialog() {
        val items = RecentPlayPreferences.getAll(this)
        if (items.isEmpty()) {
            Toast.makeText(this, "暂无最近播放", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("最近播放")
            .setItems(items.map { it.title }.toTypedArray()) { _, which -> playRecent(items[which]) }
            .show()
    }

    /** 查询 content uri 的显示名 */
    private fun queryDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: uri.lastPathSegment ?: "本地视频"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "本地视频"
        }
    }

    /** 统一设置入口（播放/画质）；保存后下次起播生效 */
    private fun showRenderSettingsDialog() {
        val currentBackend = RendererPreferences.getRendererBackend(this)
        val currentDecoder = DecoderPreferences.getDecoderMode(this)
        val backends = arrayOf("OpenGL ES (默认)", "Vulkan", "Metal (暂不可用)")
        val decoders = arrayOf("硬解直渲 (Surface)", "硬解Buffer", "软解 (FFmpeg)", "自动 (三级回退)")
        var selectedBackend = currentBackend
        var selectedDecoder = currentDecoder

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        // 分区标题：首个无上间距，其余 20dp 拉开分组
        fun sectionHeader(title: String, first: Boolean = false) {
            dialogView.addView(TextView(this).apply {
                text = title; setTypeface(null, android.graphics.Typeface.BOLD)
                if (!first) layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(20) }
            })
        }

        sectionHeader("渲染后端", first = true)
        val backendGroup = RadioGroup(this)
        backends.forEachIndexed { index, name ->
            backendGroup.addView(android.widget.RadioButton(this).apply {
                text = name; id = index + 100; isChecked = index == currentBackend; isEnabled = index != 2
            })
        }
        backendGroup.setOnCheckedChangeListener { _, id -> if (id - 100 != 2) selectedBackend = id - 100 }
        dialogView.addView(backendGroup)

        sectionHeader("解码模式")
        val decoderGroup = RadioGroup(this)
        decoders.forEachIndexed { index, name ->
            decoderGroup.addView(android.widget.RadioButton(this).apply {
                text = name; id = index + 200; isChecked = index == currentDecoder
            })
        }
        decoderGroup.setOnCheckedChangeListener { _, id -> selectedDecoder = id - 200 }
        dialogView.addView(decoderGroup)

        sectionHeader("画质滤镜")
        val lutPath = FilterPreferences.getFilterFilePath(this)
        dialogView.addView(TextView(this).apply {
            text = if (lutPath.isNullOrEmpty()) "滤镜文件：未设置" else "滤镜文件：${java.io.File(lutPath).name}"
        })
        dialogView.addView(Button(this).apply {
            text = "选择滤镜文件 (512x512 PNG)"
            setOnClickListener { lutPickerLauncher.launch("image/*") }
        })

        val scrollView = android.widget.ScrollView(this).apply { addView(dialogView) }
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(scrollView)
            .setPositiveButton("确定") { _, _ ->
                RendererPreferences.setRendererBackend(this, selectedBackend)
                DecoderPreferences.setDecoderMode(this, selectedDecoder)
                // 立即生效：回灌新配置并原地重开当前片（恢复进度）
                applyRenderSettings()
                if (hasVideoSource) mSkyVideoView.reloadCurrent()
                Toast.makeText(this, "设置已生效", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 设置旋转按钮监听
     * 点击切换横竖屏
     */
    private fun setupRotateButton() {
        mSkyVideoView.setOnRotateButtonClickListener {
            val currentOrientation = resources.configuration.orientation
            requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            Log.i(TAG, "Screen rotation toggled, new orientation: $requestedOrientation")
        }
    }

    /**
     * 配置画质面板：填充滤镜列表 + 处理选择/强度回调。
     * 入口为播控栏「画质」按钮，点击后由面板（竖屏下弹/横屏右弹）展示。
     */
    private fun setupFilterButton() {
        val items = mutableListOf(
            SkyQualityPanel.QualityFilterItem(FILTER_NONE, "无", "原画")
        )
        LUT_PRESETS.forEach { (id, name) ->
            items.add(SkyQualityPanel.QualityFilterItem(id, name))
        }
        items.add(SkyQualityPanel.QualityFilterItem(FILTER_DEFAULT, "默认", "自选文件"))

        mSkyVideoView.setQualityFilterItems(items)
        mSkyVideoView.setSelectedQualityFilter(FILTER_NONE)
        mSkyVideoView.setQualityIntensity(currentLutIntensity)
        mSkyVideoView.setEnhanceValues(currentEnhanceSharpness, currentEnhanceDeband)
        mSkyVideoView.setEnhance(currentEnhanceSharpness / 100f, currentEnhanceDeband / 100f)

        mSkyVideoView.setOnQualityPanelListener(object : SkyQualityPanel.OnQualityPanelListener {
            override fun onFilterSelected(item: SkyQualityPanel.QualityFilterItem) {
                applyFilter(item.id, item.title)
            }
            override fun onIntensityChanged(percent: Int) {
                currentLutIntensity = percent
                // 复用当前滤镜数据，仅以新强度重新应用
                currentLutRgba?.let { mSkyVideoView.setLut(it, percent / 100f) }
            }
            override fun onEnhanceChanged(sharpness: Int, deband: Int) {
                currentEnhanceSharpness = sharpness
                currentEnhanceDeband = deband
                mSkyVideoView.setEnhance(sharpness / 100f, deband / 100f)
            }
            override fun onCompareToggle(enabled: Boolean) {
                mSkyVideoView.setCompareEnabled(enabled)
            }
        })
    }

    private fun applyFilter(id: String, name: String) {
        when (id) {
            FILTER_NONE -> {
                currentLutRgba = null
                mSkyVideoView.setLut(null)
                Toast.makeText(this, "已关闭画质滤镜", Toast.LENGTH_SHORT).show()
                return
            }
            FILTER_DEFAULT -> {
                val path = FilterPreferences.getFilterFilePath(this)
                if (path.isNullOrEmpty()) {
                    Toast.makeText(this, "请先在「渲染设置」中选择滤镜文件", Toast.LENGTH_LONG).show()
                    return
                }
                val rgba = LutLoader.fromFile(path)
                if (rgba == null) {
                    Toast.makeText(this, "默认滤镜加载失败（需 512x512 PNG）", Toast.LENGTH_SHORT).show()
                    return
                }
                currentLutRgba = rgba
                mSkyVideoView.setLut(rgba, currentLutIntensity / 100f)
                Toast.makeText(this, "已应用：$name", Toast.LENGTH_SHORT).show()
            }
            else -> {
                val rgba = LutLoader.fromAsset(this, "lut/$id.png")
                if (rgba == null) {
                    Toast.makeText(this, "滤镜加载失败：$name", Toast.LENGTH_SHORT).show()
                    return
                }
                currentLutRgba = rgba
                mSkyVideoView.setLut(rgba, currentLutIntensity / 100f)
                Toast.makeText(this, "已应用：$name", Toast.LENGTH_SHORT).show()
            }
        }
        // 硬解直渲模式下滤镜不经过渲染器，提示用户
        if (mSkyVideoView.getActiveDecoderMode() == 0) {
            Toast.makeText(this, "提示：硬解直渲模式下滤镜不生效，请切换到软解/硬解Buffer", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 设置调试信息按钮监听
     * 点击展示当前渲染配置信息
     */
    private fun setupDebugButton() {
        mSkyVideoView.setOnDebugButtonClickListener {
            val rendererBackend = mSkyVideoView.getRendererBackend()
            val decoderMode = mSkyVideoView.getDecoderMode()

            val rendererName = when (rendererBackend) {
                0 -> "OpenGL ES"
                1 -> "Vulkan"
                2 -> "Metal"
                else -> "Unknown"
            }
            val decoderName = if (decoderMode == 3) {
                val activeMode = mSkyVideoView.getActiveDecoderMode()
                val activeName = when (activeMode) {
                    0 -> "硬解直渲"
                    1 -> "硬解Buffer"
                    2 -> "软解"
                    else -> "未知"
                }
                "自动 → $activeName"
            } else {
                when (decoderMode) {
                    0 -> "硬解直渲 (Surface)"
                    1 -> "硬解Buffer"
                    2 -> "软解 (FFmpeg)"
                    else -> "Unknown"
                }
            }

            val orientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "横屏" else "竖屏"
            val isPlaying = mSkyVideoView.isPlaying()
            val currentPos = mSkyVideoView.getCurrentPosition()
            val duration = mSkyVideoView.getDuration()

            val debugInfo = "渲染:$rendererName | 解码:$decoderName | $orientation | ${if (isPlaying) "▶" else "⏸"} ${formatDebugTime(currentPos)}/${formatDebugTime(duration)}"

            val alertDialog = AlertDialog.Builder(this)
                .setTitle("渲染配置信息")
                .setMessage("渲染后端: $rendererName\n解码模式: $decoderName\n屏幕方向: $orientation\n播放状态: ${if (isPlaying) "播放中" else "暂停"}\n播放进度: ${formatDebugTime(currentPos)} / ${formatDebugTime(duration)}")
                .setPositiveButton("确定", null)
                .create()

            alertDialog.window?.let { window ->
                val layoutParams = window.attributes
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    layoutParams.width = (resources.displayMetrics.widthPixels * 0.5).toInt()
                } else {
                    layoutParams.width = (resources.displayMetrics.widthPixels * 0.85).toInt()
                }
                window.attributes = layoutParams
            }

            alertDialog.show()
            Log.i(TAG, "Debug info: $debugInfo")
        }
    }

    /**
     * 格式化调试时间显示
     */
    private fun formatDebugTime(timeMs: Int): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    /**
     * 设置 Seek 完成监听器
     * Seek 完成后清空字幕队列并显示"字幕加载中..."提示
     */
    private fun setupSeekCompleteListener() {
        mSkyVideoView.setOnSeekCompleteListener(object : IMediaPlayer.OnSeekCompleteListener {
            override fun onSeekComplete(mp: IMediaPlayer) {
                if (!isSubtitleSyncEnabled) return

                Log.i(TAG, "Seek complete: clearing subtitle queue and showing loading hint")

                // 清空字幕等待队列
                synchronized(pendingSubtitleQueue) {
                    pendingSubtitleQueue.clear()
                }

                // 停止字幕队列检查器
                subtitleCheckRunnable?.let { subtitleHandler.removeCallbacks(it) }
                subtitleCheckRunnable = null

                // 显示"字幕加载中..."提示
                isWaitingForSubtitleAfterSeek = true
                runOnUiThread {
                    mSkyVideoView.setSubtitleText("字幕加载中...")
                }
            }
        })
    }

    /**
     * 设置字幕设置变更监听器
     */
    private fun setupSubtitleSettingsListener() {
        mSkyVideoView.setOnSubtitleSettingsChangeListener(object : OnSubtitleSettingsChangeListener {
            override fun onSubtitleSettingsChanged(settings: SubtitleSettings) {
                Log.i(TAG, "Subtitle settings changed: $settings")
                handleSubtitleSettingsChange(settings)
            }
        })
    }

    /**
     * 处理字幕设置变更
     * 只有当 enabled 状态发生变化时才触发开启/关闭操作
     */
    private fun handleSubtitleSettingsChange(settings: SubtitleSettings) {
        // 同步处理间隔和调试模式设置
        subtitleProcessingInterval = settings.processingInterval
        isSubtitleDebugMode = settings.debugMode
        Log.d(TAG, "Subtitle settings: interval=${subtitleProcessingInterval}s, debugMode=$isSubtitleDebugMode")

        // 只有 enabled 状态变化时才触发开启/关闭操作
        if (settings.enabled != lastSubtitleEnabled) {
            lastSubtitleEnabled = settings.enabled
            if (settings.enabled) {
                enableWhisperSubtitle(settings)
            } else {
                disableWhisperSubtitle()
            }
        } else {
            // enabled 状态未变化，只是其他设置变更（如语言、设备），仅记录日志
            Log.d(TAG, "Subtitle settings updated (enabled unchanged): $settings")
        }
    }

    /**
     * 启用 Whisper AI 字幕
     */
    private fun enableWhisperSubtitle(settings: SubtitleSettings) {
        val app = application as? SkyPlayerApplication
        val modelPath = app?.getWhisperModelPath()

        if (modelPath == null) {
            Toast.makeText(this, "模型加载中，请稍后再试", Toast.LENGTH_SHORT).show()
            mSkyVideoView.setSubtitleSettings(settings.copy(enabled = false))
            return
        }

        // 显示预缓冲 UI
        showPrebufferUI()

        // 设置预缓冲完成监听器
        mSkyVideoView.getMediaPlayer()?.let { player ->
            player.clearSubtitleQueue()

            player.setOnPrebufferCompleteListener(object : SkyMediaPlayer.OnPrebufferCompleteListener {
                override fun onPrebufferComplete(mp: IMediaPlayer, subtitleCount: Int) {
                    Log.i(TAG, "Prebuffer complete: $subtitleCount subtitles")
                    runOnUiThread {
                        hidePrebufferUI()
                        startSubtitleSync()
                        player.start()
                        Toast.makeText(this@SkyVideoActivity, "AI 字幕准备完成", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        // 暂停视频，等待预缓冲
        mSkyVideoView.pause()

        // 根据设置确定语言
        val language = settings.targetLanguage.code.ifEmpty {
            "en" // 默认英文识别
        }

        val result = mSkyVideoView.setWhisperEnabled(true, modelPath, language, settings.processingInterval)
        if (result != 0) {
            Toast.makeText(this, "AI 字幕开启失败", Toast.LENGTH_SHORT).show()
            mSkyVideoView.setSubtitleSettings(settings.copy(enabled = false))
            hidePrebufferUI()
            mSkyVideoView.start()
        } else {
            Log.i(TAG, "Whisper enabled with device=${settings.inferenceDevice}, language=$language")
        }
    }

    /**
     * 禁用 Whisper AI 字幕
     */
    private fun disableWhisperSubtitle() {
        stopSubtitleSync()
        mSkyVideoView.hideSubtitle()
        mSkyVideoView.setWhisperEnabled(false)
        Toast.makeText(this, "AI 字幕已关闭", Toast.LENGTH_SHORT).show()
    }

    /**
     * 创建预缓冲 UI
     */
    private fun createPrebufferUI() {
        val rootView = findViewById<FrameLayout>(R.id.sky_video_view)?.parent as? FrameLayout
            ?: return

        prebufferOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x80000000.toInt())
            visibility = View.GONE
        }

        prebufferProgress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        prebufferText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = 150
            }
            text = "正在准备 AI 字幕..."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
        }

        prebufferOverlay?.addView(prebufferProgress)
        prebufferOverlay?.addView(prebufferText)
        rootView.addView(prebufferOverlay)
    }

    /**
     * 显示预缓冲 UI
     */
    private fun showPrebufferUI() {
        prebufferOverlay?.visibility = View.VISIBLE
    }

    /**
     * 隐藏预缓冲 UI
     */
    private fun hidePrebufferUI() {
        prebufferOverlay?.visibility = View.GONE
    }

    /**
     * 启动字幕同步
     * 实现字幕时间窗口控制：
     * - 立即展示：主时钟在 [startTime - interval/2, startTime + interval/2]
     * - 丢弃：startTime + interval < 主时钟（字幕太旧）
     * - 等待：字幕早于主时钟，加入等待队列，等待合适时间再展示
     */
    private fun startSubtitleSync() {
        if (isSubtitleSyncEnabled) return
        isSubtitleSyncEnabled = true

        // 清空字幕队列
        synchronized(pendingSubtitleQueue) {
            pendingSubtitleQueue.clear()
        }

        // 使用带时间戳的回调方式显示字幕
        mSkyVideoView.getMediaPlayer()?.let { player ->
            player.setOnSubtitleWithPtsListener(object : SkyMediaPlayer.OnSubtitleWithPtsListener {
                override fun onSubtitle(mp: IMediaPlayer, text: String, startTimeMs: Long, endTimeMs: Long) {
                    if (!isSubtitleSyncEnabled) return

                    // Seek 后收到第一条新字幕，清除"字幕加载中..."状态
                    if (isWaitingForSubtitleAfterSeek) {
                        isWaitingForSubtitleAfterSeek = false
                        Log.i(TAG, "First subtitle after seek received, clearing loading hint")
                    }

                    // 获取当前播放位置（毫秒）
                    val currentPosMs = mp.getCurrentPosition()
                    val intervalMs = subtitleProcessingInterval * 1000L
                    val halfIntervalMs = intervalMs / 2

                    // 计算时间窗口边界
                    val windowStart = startTimeMs - halfIntervalMs
                    val windowEnd = startTimeMs + halfIntervalMs
                    val discardThreshold = startTimeMs + intervalMs

                    when {
                        // 丢弃条件：字幕太旧，startTime + interval < 主时钟
                        discardThreshold < currentPosMs -> {
                            Log.d(TAG, "Subtitle discarded (too old): startTime=${startTimeMs}ms, current=${currentPosMs}ms, threshold=${discardThreshold}ms")
                            // 不显示，直接丢弃
                        }

                        // 立即展示条件：主时钟在 [startTime - interval/2, startTime + interval/2]
                        currentPosMs in windowStart..windowEnd -> {
                            runOnUiThread {
                                displaySubtitle(text, startTimeMs, currentPosMs)
                            }
                        }

                        // 等待条件：字幕超前（startTime > currentPos + halfInterval）
                        startTimeMs > currentPosMs + halfIntervalMs -> {
                            // 将字幕加入等待队列
                            synchronized(pendingSubtitleQueue) {
                                pendingSubtitleQueue.add(PendingSubtitle(text, startTimeMs, endTimeMs))
                                // 按 startTimeMs 排序，确保先到期的字幕先展示
                                pendingSubtitleQueue.sortBy { it.startTimeMs }
                                Log.d(TAG, "Subtitle queued: startTime=${startTimeMs}ms, current=${currentPosMs}ms, queueSize=${pendingSubtitleQueue.size}")
                            }
                            // 启动字幕队列检查器
                            startSubtitleQueueChecker(mp)
                        }

                        // 其他情况：直接显示
                        else -> {
                            runOnUiThread {
                                displaySubtitle(text, startTimeMs, currentPosMs)
                            }
                        }
                    }
                }
            })
        }
    }

    /**
     * 启动字幕队列检查器
     * 定期检查等待队列中的字幕是否到达展示时间
     */
    private fun startSubtitleQueueChecker(player: IMediaPlayer) {
        // 如果检查器已经在运行，不需要重复启动
        if (subtitleCheckRunnable != null) return

        subtitleCheckRunnable = object : Runnable {
            override fun run() {
                if (!isSubtitleSyncEnabled) {
                    subtitleCheckRunnable = null
                    return
                }

                val currentPosMs = player.getCurrentPosition()
                val intervalMs = subtitleProcessingInterval * 1000L
                val halfIntervalMs = intervalMs / 2

                synchronized(pendingSubtitleQueue) {
                    // 遍历队列，处理到期的字幕
                    val iterator = pendingSubtitleQueue.iterator()
                    while (iterator.hasNext()) {
                        val subtitle = iterator.next()
                        val windowStart = subtitle.startTimeMs - halfIntervalMs
                        val windowEnd = subtitle.startTimeMs + halfIntervalMs
                        val discardThreshold = subtitle.startTimeMs + intervalMs

                        when {
                            // 字幕已过期，丢弃
                            discardThreshold < currentPosMs -> {
                                Log.d(TAG, "Queued subtitle discarded (too old): startTime=${subtitle.startTimeMs}ms, current=${currentPosMs}ms")
                                iterator.remove()
                            }
                            // 字幕到达展示时间窗口，展示并移除
                            currentPosMs in windowStart..windowEnd -> {
                                runOnUiThread {
                                    displaySubtitle(subtitle.text, subtitle.startTimeMs, currentPosMs)
                                }
                                iterator.remove()
                                Log.d(TAG, "Queued subtitle displayed: startTime=${subtitle.startTimeMs}ms, current=${currentPosMs}ms")
                            }
                            // 字幕还未到展示时间，继续等待（队列已排序，后面的字幕更晚，可以跳出）
                            else -> {
                                // 继续检查下一个，因为可能有更早的字幕已经到期
                            }
                        }
                    }

                    // 如果队列为空，停止检查器
                    if (pendingSubtitleQueue.isEmpty()) {
                        subtitleCheckRunnable = null
                        return
                    }
                }

                // 继续下一次检查
                subtitleHandler.postDelayed(this, SUBTITLE_CHECK_INTERVAL_MS)
            }
        }

        // 启动检查器
        subtitleHandler.post(subtitleCheckRunnable!!)
    }

    /**
     * 显示字幕
     * @param text 字幕文本
     * @param startTimeMs 字幕开始时间（毫秒）
     * @param currentPosMs 当前播放位置（毫秒）
     */
    private fun displaySubtitle(text: String, startTimeMs: Long, currentPosMs: Long) {
        val currentPosSec = currentPosMs / 1000.0
        val startTimeSec = startTimeMs / 1000.0
        val delaySec = startTimeSec - currentPosSec

        val displayText = if (isSubtitleDebugMode) {
            // 调试模式：显示时间信息
            // 格式：[字幕时间 | 播放时间 | 延迟] 字幕内容
            String.format("[%.1fs | %.1fs | %.1fs] %s",
                startTimeSec, currentPosSec, delaySec, text)
        } else {
            // 正常模式：只显示字幕
            text
        }

        currentSubtitleText = displayText
        mSkyVideoView.setSubtitleText(displayText)
        Log.d(TAG, "Subtitle displayed: $displayText")
    }

    /**
     * 设置字幕调试模式
     * @param enabled true 开启调试模式，显示时间信息；false 关闭调试模式，只显示字幕
     */
    fun setSubtitleDebugMode(enabled: Boolean) {
        isSubtitleDebugMode = enabled
        Log.i(TAG, "Subtitle debug mode: $enabled")
    }

    /**
     * 停止字幕同步
     */
    private fun stopSubtitleSync() {
        isSubtitleSyncEnabled = false
        isWaitingForSubtitleAfterSeek = false
        // 停止字幕队列检查器
        subtitleCheckRunnable?.let { subtitleHandler.removeCallbacks(it) }
        subtitleCheckRunnable = null
        // 清空字幕等待队列
        synchronized(pendingSubtitleQueue) {
            pendingSubtitleQueue.clear()
        }
        // AUTO_TEST 路由会在初始化 view 前 finish，onDestroy 调到此处需防未初始化
        if (::mSkyVideoView.isInitialized) {
            mSkyVideoView.getMediaPlayer()?.setOnSubtitleWithPtsListener(null)
        }
        currentSubtitleText = null
    }

    private fun setupFullscreenMode() {
        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 使用 WindowCompat 和 WindowInsetsControllerCompat 实现全沉浸式
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        windowInsetsController?.let { controller ->
            // 隐藏状态栏和导航栏
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // 设置沉浸式模式，用户滑动时系统栏会暂时显示然后自动隐藏
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 设置全屏标志（兼容旧版本）
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 当窗口重新获得焦点时，重新设置全屏模式
            setupFullscreenMode()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause() called")

        if (::mSkyVideoView.isInitialized) {
            wasPlayingBeforePause = mSkyVideoView.isPlaying()
            if (wasPlayingBeforePause) {
                mSkyVideoView.pause()
                Log.d(TAG, "Video paused in onPause()")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() called")

        if (::mSkyVideoView.isInitialized && wasPlayingBeforePause) {
            mSkyVideoView.post {
                mSkyVideoView.start()
                wasPlayingBeforePause = false
                Log.d(TAG, "Video resumed in onResume() after Surface ready")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() called - releasing all resources")

        // 停止字幕同步
        stopSubtitleSync()

        // 释放所有资源，确保音频完全停止
        if (::mSkyVideoView.isInitialized) {
            mSkyVideoView.release()
            Log.d(TAG, "SkyVideoView resources released")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Log.d(TAG, "onBackPressed() called")
        super.onBackPressed()
    }
}