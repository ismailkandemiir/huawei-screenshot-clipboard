package com.ismail.screenshotclipboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnBatteryOptimization: Button
    private lateinit var switchToast: SwitchMaterial

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startWatcherService()
        } else {
            Toast.makeText(
                this,
                "Ekran görüntülerini algılamak için fotoğraf/medya izni gereklidir.",
                Toast.LENGTH_LONG
            ).show()
            updateUiState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnToggleService = findViewById(R.id.btnToggleService)
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization)
        switchToast = findViewById(R.id.switchToast)

        val prefs = getSharedPreferences(ScreenshotWatcherService.PREFS_NAME, Context.MODE_PRIVATE)
        switchToast.isChecked = prefs.getBoolean(ScreenshotWatcherService.KEY_SHOW_TOAST, true)

        switchToast.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ScreenshotWatcherService.KEY_SHOW_TOAST, isChecked).apply()
        }

        btnToggleService.setOnClickListener {
            if (ScreenshotWatcherService.isRunning) {
                stopWatcherService()
            } else {
                checkPermissionsAndStart()
            }
        }

        btnBatteryOptimization.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        // İlk açılışta izinleri kontrol edip otomatik başlat
        if (!ScreenshotWatcherService.isRunning && prefs.getBoolean(ScreenshotWatcherService.KEY_SERVICE_ENABLED, true)) {
            checkPermissionsAndStart()
        }
        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun checkPermissionsAndStart() {
        val neededPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            startWatcherService()
        }
    }

    private fun startWatcherService() {
        val prefs = getSharedPreferences(ScreenshotWatcherService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(ScreenshotWatcherService.KEY_SERVICE_ENABLED, true).apply()

        val intent = Intent(this, ScreenshotWatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        tvServiceStatus.postDelayed({ updateUiState() }, 200)
    }

    private fun stopWatcherService() {
        val prefs = getSharedPreferences(ScreenshotWatcherService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(ScreenshotWatcherService.KEY_SERVICE_ENABLED, false).apply()

        val intent = Intent(this, ScreenshotWatcherService::class.java)
        stopService(intent)

        tvServiceStatus.postDelayed({ updateUiState() }, 200)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        } else {
            Toast.makeText(this, "Pil kısıtlaması zaten kaldırılmış!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUiState() {
        if (ScreenshotWatcherService.isRunning) {
            tvServiceStatus.text = "✅ Durum: Aktif (Ekran görüntüleri kopyalanıyor)"
            tvServiceStatus.setTextColor(Color.parseColor("#166534"))
            btnToggleService.text = "Servisi Durdur"
        } else {
            tvServiceStatus.text = "⏸️ Durum: Durduruldu"
            tvServiceStatus.setTextColor(Color.parseColor("#991B1B"))
            btnToggleService.text = "Servisi Başlat"
        }
    }
}
