package com.ble.resistancemeter.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content. Intent
import android.content.pm.PackageManager
import android.location. Location
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app. ActivityCompat
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
import kotlin.random.Random

class MeasurementService :  Service() {

    companion object {
        private const val CHANNEL_ID = "measurement_channel"
        private const val NOTIF_ID = 1
        private const val MAX_BUFFER_SIZE = 99

        const val ACTION_START = "com. ble.resistancemeter.action.START"
        const val ACTION_STOP = "com. ble.resistancemeter.action. STOP"
        const val ACTION_UPDATE_PARAMS = "com. ble.resistancemeter.action.UPDATE_PARAMS"
        const val ACTION_A_UPDATE = "com. ble.resistancemeter.action.A_UPDATE"
        const val ACTION_BLE_DATA = "com.fayvince.alkalmazas.action. BLE_DATA"
        const val ACTION_ELAPSED_TIME_UPDATE = "com. ble.resistancemeter.action. ELAPSED_TIME_UPDATE"
        const val ACTION_START_DEMO = "com. ble.resistancemeter.action.START_DEMO"
        const val ACTION_STOP_DEMO = "com. ble.resistancemeter.action. STOP_DEMO"
        const val ACTION_STOP_FROM_NOTIFICATION = "com. ble.resistancemeter.action. STOP_FROM_NOTIFICATION"
        const val ACTION_GPS_STATUS = "com. ble.resistancemeter.action.GPS_STATUS"

        const val EXTRA_N = "extra_n"
        const val EXTRA_B = "extra_b"
        const val EXTRA_A_VALUE = "extra_a_value"
        const val EXTRA_RAW_VALUE = "raw_value"
        const val EXTRA_ELAPSED_TIME = "extra_elapsed_time"
        const val EXTRA_GPS_READY = "extra_gps_ready"

        private val measurements = mutableListOf<Measurement>()
        private val parameterChanges = mutableListOf<ParameterChange>()
        private var startTime:  String = ""

        fun getMeasurements(): List<Measurement> = measurements
        fun getParameterChanges(): List<ParameterChange> = parameterChanges
        fun getStartTime(): String = startTime
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var notificationManager: NotificationManager
    private val gson:  Gson = GsonBuilder().setPrettyPrinting().create()

    private var lastKnownNonZeroLocation: Location?  = null

    private val sampleBuffer = ArrayDeque<Int>()
    private var nValue = 10
    private var bValue = 5

    private var measurementData: MeasurementData? = null
    private var sessionFile: File? = null

    private var startTimeMillis = 0L
    private val updateHandler = Handler(Looper.getMainLooper())
    private val saveHandler = Handler(Looper.getMainLooper())
    private val demoHandler = Handler(Looper.getMainLooper())

    private val demoRunnable = object : Runnable {
        override fun run() {
            val randomValue = Random.nextInt(500, 2000)
            onBleMeasurement(randomValue)
            demoHandler.postDelayed(this, 1000)
        }
    }

    private val notificationUpdateRunnable = object :  Runnable {
        override fun run() {
            updateNotification()
            broadcastElapsedTime()
            updateHandler.postDelayed(this, 1000)
        }
    }

    private val saveMeasurementRunnable = object :  Runnable {
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
            ACTION_START_DEMO -> startDemoGeneration()
            ACTION_STOP_DEMO -> stopDemoGeneration()
            ACTION_UPDATE_PARAMS -> updateParameters(intent)
            ACTION_BLE_DATA -> {
                val rawValue = intent.getIntExtra(EXTRA_RAW_VALUE, 0)
                onBleMeasurement(rawValue)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent? ): IBinder? {
        return null
    }

    override fun onDestroy() {
        super. onDestroy()
        stopLocationUpdates()
        updateHandler.removeCallbacks(notificationUpdateRunnable)
        saveHandler. removeCallbacks(saveMeasurementRunnable)
        stopDemoGeneration()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION. SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string. measurement_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object :  LocationCallback() {
            override fun onLocationResult(locationResult:  LocationResult) {
                for (location in locationResult.locations) {
                    if (location.latitude != 0.0 || location.longitude != 0.0) {
                        val wasGpsReady = lastKnownNonZeroLocation != null
                        lastKnownNonZeroLocation = location

                        if (! wasGpsReady) {
                            // GPS kész - broadcast küldése a UI frissítéséhez
                            broadcastGpsStatus(true)
                            startTimers()
                        }
                    }
                }
            }
        }
    }

    private fun broadcastGpsStatus(ready: Boolean) {
        val intent = Intent(ACTION_GPS_STATUS)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_GPS_READY, ready)
        sendBroadcast(intent)
    }

    private fun loadParameters() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        nValue = prefs. getInt("n_value", 10).coerceIn(1, 99)
        bValue = prefs.getInt("b_value", 5).coerceIn(1, 999)
    }

    private fun startMeasurement() {
        startTimeMillis = System.currentTimeMillis()
        sampleBuffer.clear()
        measurements.clear()
        parameterChanges. clear()
        startTime = ""
        lastKnownNonZeroLocation = null

        createSessionFile()
        startLocationUpdates()

        // GPS státusz broadcast - még nincs kész
        broadcastGpsStatus(false)

        val notification = buildNotification()
        startForeground(NOTIF_ID, notification)

        updateHandler.post(notificationUpdateRunnable)
    }

    private fun startTimers() {
        saveHandler.removeCallbacks(saveMeasurementRunnable)
        saveHandler.postDelayed(saveMeasurementRunnable, bValue * 1000L)
    }

    private fun stopMeasurement() {
        stopLocationUpdates()
        updateHandler.removeCallbacks(notificationUpdateRunnable)
        saveHandler.removeCallbacks(saveMeasurementRunnable)
        stopDemoGeneration()

        if (Build.VERSION. SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startDemoGeneration() {
        demoHandler.removeCallbacks(demoRunnable)
        demoHandler.post(demoRunnable)
    }

    private fun stopDemoGeneration() {
        demoHandler.removeCallbacks(demoRunnable)
    }

    private fun updateParameters(intent: Intent) {
        val newN = intent.getIntExtra(EXTRA_N, nValue).coerceIn(1, 99)
        val newB = intent.getIntExtra(EXTRA_B, bValue).coerceIn(1, 999)

        val paramsChanged = newN != nValue || newB != bValue
        nValue = newN
        bValue = newB

        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("n_value", nValue)
            .putInt("b_value", bValue)
            .apply()

        if (paramsChanged) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH: mm:ss", Locale.getDefault()).format(Date())
            val paramChange = ParameterChange(timestamp, nValue, B = bValue)
            measurementData?.parameterChanges?.add(paramChange)
            parameterChanges.add(paramChange)
            flushSessionFile()

            if (lastKnownNonZeroLocation != null) {
                saveHandler.removeCallbacks(saveMeasurementRunnable)
                saveHandler.postDelayed(saveMeasurementRunnable, bValue * 1000L)
            }
        }
    }

    private fun createSessionFile() {
        val dateFormat = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault())
        val fileName = "${dateFormat.format(Date())}_GPS. json"

        val documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        documentsDir?. mkdirs()

        sessionFile = File(documentsDir, fileName)

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        startTime = timestamp
        measurementData = MeasurementData(startTime = timestamp)

        val paramChange = ParameterChange(timestamp, nValue, B = bValue)
        measurementData?.parameterChanges?.add(paramChange)
        parameterChanges. add(paramChange)

        flushSessionFile()
    }

    private fun flushSessionFile() {
        sessionFile?.let { file ->
            try {
                FileWriter(file).use { writer ->
                    gson.toJson(measurementData, writer)
                }
            } catch (e:  Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val locationRequest = LocationRequest. Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
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

    fun onBleMeasurement(rawValue: Int) {
        sampleBuffer.addLast(rawValue)

        while (sampleBuffer. size > MAX_BUFFER_SIZE) {
            sampleBuffer.removeFirst()
        }

        val aValue = calculateMovingAverage()

        val intent = Intent(ACTION_A_UPDATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_A_VALUE, aValue)
        sendBroadcast(intent)
    }

    private fun calculateMovingAverage(): Double {
        val samplesToUse = sampleBuffer.toList().takeLast(nValue)
        return if (samplesToUse.isNotEmpty()) {
            samplesToUse. average()
        } else {
            0.0
        }
    }

    private fun saveMeasurement() {
        if (lastKnownNonZeroLocation == null) return

        val aValue = calculateMovingAverage()
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm: ss", Locale. getDefault()).format(Date())

        val location = lastKnownNonZeroLocation!!
        val measurement = Measurement(timestamp, aValue, location.latitude, location.longitude)

        measurementData?.measurements?. add(measurement)
        measurements.add(measurement)
        flushSessionFile()
    }

    private fun buildNotification(): android.app.Notification {
        val elapsedSeconds = if (startTimeMillis > 0) {
            (System. currentTimeMillis() - startTimeMillis) / 1000
        } else {
            0
        }

        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        val flag = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val contentIntent = Intent(this, MainActivity:: class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent. FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent. getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flag
        )

        val stopIntent = Intent(ACTION_STOP_FROM_NOTIFICATION)
        stopIntent.setPackage(packageName)

        val stopPendingIntent = PendingIntent. getBroadcast(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flag
        )

        val title = if (lastKnownNonZeroLocation == null) getString(R.string. waiting_for_gps) else getString(R.string. measurement_in_progress)

        return NotificationCompat. Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string. elapsed_time_notif, timeString))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_stat_name, getString(R.string.stop), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        notificationManager.notify(NOTIF_ID, notification)
    }

    private fun broadcastElapsedTime() {
        val elapsedSeconds = if (startTimeMillis > 0) {
            (System.currentTimeMillis() - startTimeMillis) / 1000
        } else {
            0
        }
        val intent = Intent(ACTION_ELAPSED_TIME_UPDATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_ELAPSED_TIME, elapsedSeconds)
        sendBroadcast(intent)
    }
}