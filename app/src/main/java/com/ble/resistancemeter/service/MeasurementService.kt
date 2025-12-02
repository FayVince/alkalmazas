package com.ble.resistancemeter.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.ble.resistancemeter.R
import com.ble.resistancemeter.data.Measurement
import com.ble.resistancemeter.data.MeasurementData
import com.ble.resistancemeter.data.ParameterChange
import com.ble.resistancemeter.ui.MainActivity
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class MeasurementService : Service() {
    
    companion object {
        private const val CHANNEL_ID = "measurement_channel"
        private const val NOTIF_ID = 1
        private const val MAX_BUFFER_SIZE = 99
        
        const val ACTION_START = "com.ble.resistancemeter.action.START"
        const val ACTION_STOP = "com.ble.resistancemeter.action.STOP"
        const val ACTION_UPDATE_PARAMS = "com.ble.resistancemeter.action.UPDATE_PARAMS"
        const val ACTION_A_UPDATE = "com.ble.resistancemeter.action.A_UPDATE"
        const val ACTION_BLE_DATA = "com.fayvince.alkalmazas.action.BLE_DATA"
        
        const val EXTRA_N = "extra_n"
        const val EXTRA_B = "extra_b"
        const val EXTRA_A_VALUE = "extra_a_value"
    }
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var notificationManager: NotificationManager
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    
    private var currentLocation: Location? = null
    private var lastKnownNonZeroLocation: Location? = null
    
    private val sampleBuffer = ArrayDeque<Int>()
    private var nValue = 10
    private var bValue = 5
    
    private var measurementData: MeasurementData? = null
    private var sessionFile: File? = null
    
    private var startTime = 0L
    private val updateHandler = Handler(Looper.getMainLooper())
    private val saveHandler = Handler(Looper.getMainLooper())
    
    private val notificationUpdateRunnable = object : Runnable {
        override fun run() {
            updateNotification()
            updateHandler.postDelayed(this, 1000)
        }
    }
    
    private val saveMeasurementRunnable = object : Runnable {
        override fun run() {
            saveMeasurement()
            saveHandler.postDelayed(this, bValue * 1000L)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        createNotificationChannel()
        setupLocationCallback()
        loadParameters()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMeasurement()
            ACTION_STOP -> stopMeasurement()
            ACTION_UPDATE_PARAMS -> updateParameters(intent)
            ACTION_BLE_DATA -> {
                val rawValue = intent.getIntExtra("raw_value", 0)
                onBleMeasurement(rawValue)
            }
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        updateHandler.removeCallbacks(notificationUpdateRunnable)
        saveHandler.removeCallbacks(saveMeasurementRunnable)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Measurement Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    // Ignore 0.0/0.0 coordinates
                    if (location.latitude != 0.0 || location.longitude != 0.0) {
                        currentLocation = location
                        lastKnownNonZeroLocation = location
                    }
                }
            }
        }
    }
    
    private fun loadParameters() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        nValue = prefs.getInt("n_value", 10).coerceIn(1, 99)
        bValue = prefs.getInt("b_value", 5).coerceIn(1, 999)
    }
    
    private fun startMeasurement() {
        startTime = System.currentTimeMillis()
        sampleBuffer.clear()
        
        createSessionFile()
        startLocationUpdates()
        
        val notification = buildNotification()
        startForeground(NOTIF_ID, notification)
        
        updateHandler.post(notificationUpdateRunnable)
        saveHandler.postDelayed(saveMeasurementRunnable, bValue * 1000L)
    }
    
    private fun stopMeasurement() {
        stopLocationUpdates()
        updateHandler.removeCallbacks(notificationUpdateRunnable)
        saveHandler.removeCallbacks(saveMeasurementRunnable)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }
    
    private fun updateParameters(intent: Intent) {
        val newN = intent.getIntExtra(EXTRA_N, nValue).coerceIn(1, 99)
        val newB = intent.getIntExtra(EXTRA_B, bValue).coerceIn(1, 999)
        
        val paramsChanged = newN != nValue || newB != bValue
        nValue = newN
        bValue = newB
        
        // Save to SharedPreferences
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("n_value", nValue)
            .putInt("b_value", bValue)
            .apply()
        
        if (paramsChanged) {
            // Log parameter change
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val paramChange = ParameterChange(timestamp, nValue, B = bValue)
            measurementData?.parameterChanges?.add(paramChange)
            flushSessionFile()
            
            // Restart save timer with new B value
            saveHandler.removeCallbacks(saveMeasurementRunnable)
            saveHandler.postDelayed(saveMeasurementRunnable, bValue * 1000L)
        }
    }
    
    private fun createSessionFile() {
        val dateFormat = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault())
        val fileName = "${dateFormat.format(Date())}_GPS.json"
        
        val documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        documentsDir?.mkdirs()
        
        sessionFile = File(documentsDir, fileName)
        
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        measurementData = MeasurementData(startTime = timestamp)
        
        // Add initial parameter values
        val paramChange = ParameterChange(timestamp, nValue, B = bValue)
        measurementData?.parameterChanges?.add(paramChange)
        
        flushSessionFile()
    }
    
    private fun flushSessionFile() {
        sessionFile?.let { file ->
            try {
                FileWriter(file).use { writer ->
                    gson.toJson(measurementData, writer)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L  // 1 second interval
        ).build()
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }
    
    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
    
    /**
     * Public method for BLE integration to send raw measurement values.
     * BLE code should call this method with the raw low-high byte integer value.
     * 
     * Integration options:
     * 1. Bind to service and call this method directly
     * 2. Send a broadcast intent with the raw value
     */
    fun onBleMeasurement(rawValue: Int) {
        // Add to buffer
        sampleBuffer.addLast(rawValue)
        
        // Limit buffer to max size
        while (sampleBuffer.size > MAX_BUFFER_SIZE) {
            sampleBuffer.removeFirst()
        }
        
        // Calculate moving average
        val aValue = calculateMovingAverage()
        
        // Broadcast A value update to UI
        val intent = Intent(ACTION_A_UPDATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_A_VALUE, aValue)
        sendBroadcast(intent)
    }
    
    private fun calculateMovingAverage(): Double {
        val samplesToUse = sampleBuffer.toList().takeLast(nValue)
        return if (samplesToUse.isNotEmpty()) {
            samplesToUse.average()
        } else {
            0.0
        }
    }
    
    private fun saveMeasurement() {
        val aValue = calculateMovingAverage()
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        
        val location = lastKnownNonZeroLocation
        val measurement = if (location != null) {
            Measurement(timestamp, aValue, location.latitude, location.longitude)
        } else {
            // No valid GPS location obtained yet - using 0.0/0.0 as placeholder
            // Note: Incoming 0/0 coords from GPS are ignored (see locationCallback)
            Measurement(timestamp, aValue, 0.0, 0.0)
        }
        
        measurementData?.measurements?.add(measurement)
        flushSessionFile()
    }
    
    private fun buildNotification(): android.app.Notification {
        val elapsedSeconds = if (startTime > 0) {
            (System.currentTimeMillis() - startTime) / 1000
        } else {
            0
        }
        
        val contentIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(this, MeasurementService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kesztyű mérés fut")
            .setContentText("Futási idő: $elapsedSeconds másodperc")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Leállítás", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification() {
        val notification = buildNotification()
        notificationManager.notify(NOTIF_ID, notification)
    }
}
