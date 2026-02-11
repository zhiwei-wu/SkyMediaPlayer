package imt.skymediaplayer.demo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        const val EXTRA_RENDERER_BACKEND = "renderer_backend"
        const val EXTRA_DECODER_MODE = "decoder_mode"
    }

    private lateinit var btnRenderSettings: Button

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val intent = Intent(this, SkyVideoActivity::class.java)
            intent.putExtra("video_uri", it.toString())
            intent.putExtra(EXTRA_RENDERER_BACKEND, RendererPreferences.getRendererBackend(this))
            intent.putExtra(EXTRA_DECODER_MODE, DecoderPreferences.getDecoderMode(this))
            startActivity(intent)
        } ?: run {
            Toast.makeText(this, "未选择视频文件", Toast.LENGTH_SHORT).show()
        }
    }

    // 管理外部存储权限请求（Android 11+）
    private val manageStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "已获得文件管理权限", Toast.LENGTH_SHORT).show()
                openFilePicker()
            } else {
                Toast.makeText(this, "需要文件管理权限才能访问所有文件", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity_layout)

        // 渲染设置（包含渲染后端 + 解码模式）
        btnRenderSettings = findViewById(R.id.btn_render_settings)
        updateRenderSettingsButtonText()
        btnRenderSettings.setOnClickListener {
            showRenderSettingsDialog()
        }

        // 本地视频播放
        val btnPlayVideo = findViewById<Button>(R.id.btn_play_video)
        btnPlayVideo.setOnClickListener {
            checkPermissionsAndOpenFilePicker()
        }

        // 预设在线视频链接
        val btnPlayVideo1 = findViewById<Button>(R.id.btn_play_video_1)
        btnPlayVideo1.setOnClickListener {
            playOnlineVideo("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
        }

        val btnPlayVideo2 = findViewById<Button>(R.id.btn_play_video_2)
        btnPlayVideo2.setOnClickListener {
            playOnlineVideo("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
        }

        val btnPlayVideo3 = findViewById<Button>(R.id.btn_play_video_3)
        btnPlayVideo3.setOnClickListener {
            playOnlineVideo("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4")
        }

        // Sintel 预告片（英文语音测试，适合测试 Whisper 英文识别）
        val btnPlayCctvNews = findViewById<Button>(R.id.btn_play_cctv_news)
        btnPlayCctvNews.setOnClickListener {
            playOnlineVideo("https://media.w3.org/2010/05/sintel/trailer.mp4")
        }

        // 自定义URL播放
        val etCustomUrl = findViewById<android.widget.EditText>(R.id.et_custom_url)
        val btnPlayCustomUrl = findViewById<Button>(R.id.btn_play_custom_url)
        btnPlayCustomUrl.setOnClickListener {
            val customUrl = etCustomUrl.text.toString().trim()
            if (customUrl.isEmpty()) {
                Toast.makeText(this, "请输入视频URL", Toast.LENGTH_SHORT).show()
            } else if (!customUrl.startsWith("http://") && !customUrl.startsWith("https://")) {
                Toast.makeText(this, "URL必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
            } else {
                playOnlineVideo(customUrl)
            }
        }
    }

    /**
     * 播放在线视频
     * 支持 HTTP/HTTPS/HLS 等网络协议
     * @param videoUrl 视频URL
     */
    private fun playOnlineVideo(videoUrl: String) {
        val intent = Intent(this, SkyVideoActivity::class.java)
        intent.putExtra("video_url", videoUrl)
        intent.putExtra(EXTRA_RENDERER_BACKEND, RendererPreferences.getRendererBackend(this))
        intent.putExtra(EXTRA_DECODER_MODE, DecoderPreferences.getDecoderMode(this))
        startActivity(intent)

        Toast.makeText(this, "正在加载在线视频...", Toast.LENGTH_SHORT).show()
    }

    /**
     * 更新渲染设置按钮文字
     */
    private fun updateRenderSettingsButtonText() {
        val backend = RendererPreferences.getBackendDisplayName(RendererPreferences.getRendererBackend(this))
        val decoder = DecoderPreferences.getModeDisplayName(DecoderPreferences.getDecoderMode(this))

        btnRenderSettings.text = "解码: $decoder\n渲染: $backend"
    }

    /**
     * 显示渲染设置对话框（包含渲染后端和解码模式）
     */
    private fun showRenderSettingsDialog() {
        val currentBackend = RendererPreferences.getRendererBackend(this)
        val currentDecoder = DecoderPreferences.getDecoderMode(this)

        val backends = arrayOf("OpenGL ES (默认)", "Vulkan", "Metal (暂不可用)")
        val decoders = arrayOf("硬解直渲 (Surface)", "硬解Buffer", "软解 (FFmpeg)", "自动 (三级回退)")

        var selectedBackend = currentBackend
        var selectedDecoder = currentDecoder

        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        // 渲染后端标题
        dialogView.addView(TextView(this).apply {
            text = "渲染后端"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })

        // 渲染后端选项
        val backendGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        backends.forEachIndexed { index, name ->
            val radioButton = android.widget.RadioButton(this).apply {
                text = name
                id = index + 100
                isChecked = index == currentBackend
                isEnabled = index != 2 // Metal 暂不可用
            }
            backendGroup.addView(radioButton)
        }
        backendGroup.setOnCheckedChangeListener { _, checkedId ->
            val index = checkedId - 100
            if (index != 2) {
                selectedBackend = index
            }
        }
        dialogView.addView(backendGroup)

        // 分隔线
        dialogView.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { topMargin = 24; bottomMargin = 24 }
            setBackgroundColor(0xFFE0E0E0.toInt())
        })

        // 解码模式标题
        dialogView.addView(TextView(this).apply {
            text = "解码模式"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })

        // 解码模式选项
        val decoderGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        decoders.forEachIndexed { index, name ->
            val radioButton = android.widget.RadioButton(this).apply {
                text = name
                id = index + 200
                isChecked = index == currentDecoder
            }
            decoderGroup.addView(radioButton)
        }
        decoderGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedDecoder = checkedId - 200
        }
        dialogView.addView(decoderGroup)

        AlertDialog.Builder(this)
            .setTitle("渲染设置")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                RendererPreferences.setRendererBackend(this, selectedBackend)
                DecoderPreferences.setDecoderMode(this, selectedDecoder)
                updateRenderSettingsButtonText()
                Toast.makeText(this, "渲染设置已保存，下次播放生效", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 检查权限并打开文件选择器
     */
    private fun checkPermissionsAndOpenFilePicker() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ 使用细分权限
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED) {
                    openFilePicker()
                } else {
                    requestMediaPermissions()
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11-12 优先使用 MANAGE_EXTERNAL_STORAGE
                if (Environment.isExternalStorageManager()) {
                    openFilePicker()
                } else {
                    requestManageStoragePermission()
                }
            }
            else -> {
                // Android 10及以下使用 READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                    openFilePicker()
                } else {
                    requestStoragePermission()
                }
            }
        }
    }

    /**
     * 打开文件选择器
     */
    private fun openFilePicker() {
        videoPickerLauncher.launch("video/*")
    }

    /**
     * 请求媒体权限（Android 13+）
     */
    private fun requestMediaPermissions() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_MEDIA_VIDEO)) {
            showPermissionRationaleDialog("需要访问媒体文件权限才能选择视频文件") {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_MEDIA_VIDEO),
                    PERMISSION_REQUEST_CODE
                )
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * 请求存储权限（Android 10及以下）
     */
    private fun requestStoragePermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            showPermissionRationaleDialog("需要存储权限才能访问视频文件") {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_CODE
                )
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * 请求管理外部存储权限（Android 11+）
     */
    private fun requestManageStoragePermission() {
        showPermissionRationaleDialog(
            "为了访问所有视频文件（包括 /sdcard/Movies/ 等目录），需要授予文件管理权限。\n\n" +
            "点击确定后，请在设置页面中找到本应用并开启\"允许访问所有文件\"权限。"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageStoragePermissionLauncher.launch(intent)
            }
        }
    }

    /**
     * 显示权限说明对话框
     */
    private fun showPermissionRationaleDialog(message: String, onPositive: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("权限说明")
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> onPositive() }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "需要相应权限才能选择视频文件", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * 处理权限请求结果
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
                openFilePicker()
            } else {
                Toast.makeText(this, "权限被拒绝，无法访问视频文件", Toast.LENGTH_LONG).show()

                // 如果用户选择了"不再询问"，引导用户到设置页面
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[0])) {
                    showGoToSettingsDialog()
                }
            }
        }
    }

    /**
     * 显示前往设置的对话框
     */
    private fun showGoToSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("权限被拒绝")
            .setMessage("您已拒绝权限请求。如需使用此功能，请前往应用设置页面手动开启权限。")
            .setPositiveButton("前往设置") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}