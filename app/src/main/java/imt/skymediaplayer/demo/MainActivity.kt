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
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 启动SkyVideoActivity并传递视频URI
            val intent = Intent(this, SkyVideoActivity::class.java)
            intent.putExtra("video_uri", it.toString())
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

        val btnPlayVideo = findViewById<Button>(R.id.btn_play_video)
        btnPlayVideo.setOnClickListener {
            checkPermissionsAndOpenFilePicker()
        }
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