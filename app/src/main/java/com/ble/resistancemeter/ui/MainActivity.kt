package com.ble.resistancemeter.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ble.resistancemeter.R
import com.ble.resistancemeter.databinding.ActivityMainBinding
import com.ble.resistancemeter.repository.BleRepository
import com.ble.resistancemeter.service.MeasurementService
import com.ble.resistancemeter.viewmodel.MeasurementViewModel
import com.google.android.gms.location.*
import android.os.Looper

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MeasurementViewModel by viewModels()
    private lateinit var sharedPreferences: SharedPreferences
    
    private var isServiceRunning = false
    private var isDemoMode = false
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocationPermission()
            }
            requestBatteryOptimization()
        } else {
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
        }
    }
    
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, getString(R.string.background_location_recommended), Toast.LENGTH_LONG).show()
        }
    }
    
    private val aValueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val aValue = intent?.getDoubleExtra(MeasurementService.EXTRA_A_VALUE, 0.0) ?: 0.0
            binding.textCurrentValue.text = String.format("%.1f", aValue)
        }
    }
    
    private val elapsedTimeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val elapsedSeconds = intent?.getLongExtra(MeasurementService.EXTRA_ELAPSED_TIME, 0L) ?: 0L
            val hours = elapsedSeconds / 3600
            val minutes = (elapsedSeconds % 3600) / 60
            val seconds = elapsedSeconds % 60
            binding.textElapsedTime.text = getString(R.string.elapsed_time, String.format("%02d:%02d:%02d", hours, minutes, seconds))
        }
    }

    private val stopActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MeasurementService.ACTION_STOP_FROM_NOTIFICATION) {
                stopMeasurementService()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        sharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        
        isDemoMode = sharedPreferences.getBoolean("demo_mode", false)
        binding.switchDemoMode.isChecked = isDemoMode
        
        requestPermissions()
        setupObservers()
        setupListeners()
        loadParameters()
        
        // GPS keresés indítása az app megnyitásakor
        startGpsUpdates()
        
        val aValueFilter = IntentFilter(MeasurementService.ACTION_A_UPDATE)
        ContextCompat.registerReceiver(this, aValueReceiver, aValueFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        
        val elapsedTimeFilter = IntentFilter(MeasurementService.ACTION_ELAPSED_TIME_UPDATE)
        ContextCompat.registerReceiver(this, elapsedTimeReceiver, elapsedTimeFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val stopFilter = IntentFilter(MeasurementService.ACTION_STOP_FROM_NOTIFICATION)
        ContextCompat.registerReceiver(this, stopActionReceiver, stopFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(aValueReceiver)
        unregisterReceiver(elapsedTimeReceiver)
        unregisterReceiver(stopActionReceiver)
        stopGpsUpdates()
    }
    
    private fun startGpsUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                1000L
            ).build()
            
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    // A pozíció elérhető, a service majd használja
                }
            }
            
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }
    
    private fun stopGpsUpdates() {
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocationPermission()
            }
            requestBatteryOptimization()
        }
    }
    
    private fun requestBackgroundLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.background_location_title))
                .setMessage(getString(R.string.background_location_message))
                .setPositiveButton(getString(R.string.grant)) { _, _ ->
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton(getString(R.string.deny), null)
                .show()
        }
    }
    
    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.disable_battery_optimization_title))
                    .setMessage(getString(R.string.disable_battery_optimization_message))
                    .setPositiveButton(getString(R.string.disable)) { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }
    
    private fun loadParameters() {
        val n = sharedPreferences.getInt("n_value", 10)
        val b = sharedPreferences.getInt("b_value", 5)
        
        binding.editTextN.setText(n.toString())
        binding.editTextB.setText(b.toString())
    }
    
    private fun setupObservers() {
        viewModel.currentAValue.observe(this) { value ->
            if (!isServiceRunning) {
                binding.textCurrentValue.text = String.format("%.1f", value)
            }
        }
        
        viewModel.connectionState.observe(this) { state ->
            when (state) {
                BleRepository.ConnectionState.DISCONNECTED -> {
                    binding.textConnectionStatus.text = getString(R.string.disconnected)
                    binding.textConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
                }
                BleRepository.ConnectionState.CONNECTING -> {
                    binding.textConnectionStatus.text = getString(R.string.connecting)
                    binding.textConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.orange))
                }
                BleRepository.ConnectionState.CONNECTED -> {
                    binding.textConnectionStatus.text = getString(R.string.connected)
                    binding.textConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
                }
            }
        }
    }
    
    private fun setupListeners() {
        binding.buttonConnect.setOnClickListener {
            showBluetoothDeviceSelectionDialog()
        }
        
        binding.buttonStartStop.setOnClickListener {
            if (isServiceRunning) {
                stopMeasurementService()
            } else {
                startMeasurementService()
            }
        }
        
        binding.buttonViewMap.setOnClickListener {
            val intent = Intent(this, MapActivity::class.java)
            startActivity(intent)
        }
        
        binding.switchDemoMode.setOnCheckedChangeListener { _, isChecked ->
            isDemoMode = isChecked
            sharedPreferences.edit().putBoolean("demo_mode", isDemoMode).apply()
            
            if (isServiceRunning) {
                if (isChecked) {
                    startDemoGenerationInService()
                } else {
                    stopDemoGenerationInService()
                }
            }
        }
    }
    
    private fun showBluetoothDeviceSelectionDialog() {
        val bleRepository = BleRepository(this)
        val dialog = BluetoothDeviceSelectionDialog(this, bleRepository) { device ->
            viewModel.connectToDevice(device)
            Toast.makeText(this, getString(R.string.connecting), Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }
    
    private fun startMeasurementService() {
        val n = validateAndSaveN()
        val b = validateAndSaveB()
        
        if (n == null || b == null) {
            return
        }

        binding.textCurrentValue.text = getString(R.string.waiting_for_gps)
        
        val intent = Intent(this, MeasurementService::class.java).apply {
            action = MeasurementService.ACTION_START
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        isServiceRunning = true
        updateUIForRunningState(true)
        
        sendParametersToService(n, b)

        if (isDemoMode) {
            startDemoGenerationInService()
        }
    }
    
    private fun stopMeasurementService() {
        val intent = Intent(this, MeasurementService::class.java).apply {
            action = MeasurementService.ACTION_STOP
        }
        startService(intent)
        
        isServiceRunning = false
        updateUIForRunningState(false)

        viewModel.saveMeasurementData { fileName ->
            runOnUiThread {
                if (fileName != null) {
                    Toast.makeText(this, getString(R.string.file_saved, fileName), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.failed_to_save_file), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startDemoGenerationInService() {
        val intent = Intent(this, MeasurementService::class.java).apply {
            action = MeasurementService.ACTION_START_DEMO
        }
        startService(intent)
    }

    private fun stopDemoGenerationInService() {
        val intent = Intent(this, MeasurementService::class.java).apply {
            action = MeasurementService.ACTION_STOP_DEMO
        }
        startService(intent)
    }
    
    private fun validateAndSaveN(): Int? {
        val text = binding.editTextN.text.toString()
        val value = text.toIntOrNull()
        
        if (value == null || value !in 1..99) {
            Toast.makeText(this, getString(R.string.n_value_error), Toast.LENGTH_SHORT).show()
            return null
        }
        
        sharedPreferences.edit().putInt("n_value", value).apply()
        
        if (isServiceRunning) {
            val b = validateBValue()
            if (b != null) {
                sendParametersToService(value, b)
            }
        }
        
        return value
    }
    
    private fun validateAndSaveB(): Int? {
        val text = binding.editTextB.text.toString()
        val value = text.toIntOrNull()
        
        if (value == null || value !in 1..999) {
            Toast.makeText(this, getString(R.string.b_value_error), Toast.LENGTH_SHORT).show()
            return null
        }
        
        sharedPreferences.edit().putInt("b_value", value).apply()
        
        if (isServiceRunning) {
            val n = validateNValue()
            if (n != null) {
                sendParametersToService(n, value)
            }
        }
        
        return value
    }
    
    private fun validateNValue(): Int? {
        val text = binding.editTextN.text.toString()
        val value = text.toIntOrNull()
        return if (value != null && value in 1..99) value else null
    }
    
    private fun validateBValue(): Int? {
        val text = binding.editTextB.text.toString()
        val value = text.toIntOrNull()
        return if (value != null && value in 1..999) value else null
    }
    
    private fun sendParametersToService(n: Int, b: Int) {
        val intent = Intent(this, MeasurementService::class.java).apply {
            action = MeasurementService.ACTION_UPDATE_PARAMS
            putExtra(MeasurementService.EXTRA_N, n)
            putExtra(MeasurementService.EXTRA_B, b)
        }
        startService(intent)
    }
    
    private fun updateUIForRunningState(running: Boolean) {
        if (running) {
            binding.buttonStartStop.text = getString(R.string.stop_measurement)
            binding.editTextN.isEnabled = false
            binding.editTextB.isEnabled = false
            binding.textElapsedTime.visibility = View.VISIBLE
        } else {
            binding.buttonStartStop.text = getString(R.string.start_measurement)
            binding.editTextN.isEnabled = true
            binding.editTextB.isEnabled = true
            binding.textElapsedTime.visibility = View.GONE
        }
    }
}
