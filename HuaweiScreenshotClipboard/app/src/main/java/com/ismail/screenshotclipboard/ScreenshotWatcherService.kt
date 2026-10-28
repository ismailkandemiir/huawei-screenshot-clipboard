package com.ismail.screenshotclipboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class ScreenshotWatcherService : Service() {

    companion object {
        const val CHANNEL_ID = "screenshot_clipboard_channel"
        const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "screenshot_prefs"
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_SHOW_TOAST = "show_toast"
        var isRunning = false
    }

    private var contentObserver: ContentObserver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    // Aynı ekran görüntüsünün birden fazla kez tetiklenmesini engellemek için son işlenen ID ve zaman
    @Volatile
    private var lastProcessedId: Long = -1L
    @Volatile
    private var lastProcessedTimeMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerScreenshotObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        contentObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerScreenshotObserver() {
        contentObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                // Huawei EMUI / HarmonyOS ekran görüntüsünü kaydederken dosyayı önce oluşturup sonra yazar.
                // Dosyanın tamamen diske yazılmasını garantilemek için 350ms gecikmeyle kontrol ediyoruz.
                mainHandler.postDelayed({
                    executor.execute {
                        checkAndCopyLatestScreenshot()
                    }
                }, 350)
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )
    }

    private fun checkAndCopyLatestScreenshot() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATA
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return

                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val relPathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)

                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: ""
                val dateAddedSec = cursor.getLong(dateCol)
                val relPath = if (relPathCol != -1) (cursor.getString(relPathCol) ?: "") else ""
                val dataPath = if (dataCol != -1) (cursor.getString(dataCol) ?: "") else ""

                // Android Q+ sistemlerde yazma devam ediyorsa IS_PENDING kontrolü
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pendingUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    contentResolver.query(
                        pendingUri,
                        arrayOf(MediaStore.Images.Media.IS_PENDING),
                        null,
                        null,
                        null
                    )?.use { pCursor ->
                        if (pCursor.moveToFirst()) {
                            val isPending = pCursor.getInt(0)
                            if (isPending == 1) return // Henüz yazılıyor, bitince tekrar onChange tetiklenir
                        }
                    }
                }

                // Zaten işlediysek tekrar işleme
                if (id == lastProcessedId) return

                // Yalnızca son 15 saniye içinde çekilmiş yeni görüntüleri dikkate al
                val nowSec = System.currentTimeMillis() / 1000
                if (nowSec - dateAddedSec > 15) return

                // Ekran görüntüsü olup olmadığını kontrol et (Huawei İngilizce/Türkçe isimlendirmeleri dahil)
                val combinedInfo = "$name $relPath $dataPath".lowercase()
                val isScreenshot = combinedInfo.contains("screenshot") ||
                        combinedInfo.contains("ekran") ||
                        combinedInfo.contains("screen_shot") ||
                        combinedInfo.contains("captures")

                if (!isScreenshot) return

                // Çok kısa sürede mükerrer tetiklenmeyi önle
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastProcessedTimeMs < 800 && id == lastProcessedId) return

                lastProcessedId = id
                lastProcessedTimeMs = nowMs

                val mediaUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                copyImageToClipboard(mediaUri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun copyImageToClipboard(sourceUri: Uri) {
        try {
            // Görseli kendi FileProvider önbelleğimize kopyalıyoruz ki Ctrl+V yapılan hedef uygulama
            // (Notlar, tarayıcı, Word, WhatsApp vb.) hiçbir izin hatası almadan görseli okuyabilsin.
            val cacheDir = File(cacheDir, "clipboard")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            // Önceki geçici dosyaları temizle
            cacheDir.listFiles()?.forEach { it.delete() }

            val outFile = File(cacheDir, "latest_screenshot.png")

            contentResolver.openInputStream(sourceUri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input) ?: return
                FileOutputStream(outFile).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.flush()
                }
            } ?: return

            val providerUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                outFile
            )

            mainHandler.post {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newUri(contentResolver, "Screenshot", providerUri)
                clipboard.setPrimaryClip(clip)

                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_SHOW_TOAST, true)) {
                    Toast.makeText(
                        applicationContext,
                        "📋 Ekran görüntüsü kopyalandı (Ctrl+V ile yapıştırın)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ekran Görüntüsü Kopyalama Servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ekran görüntülerini otomatik panoya kopyalamak için arka planda çalışır."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ekran Görüntüsü -> Ctrl+V Aktif")
            .setContentText("Parmak eklemiyle alınan ekran görüntüleri otomatik kopyalanır.")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
