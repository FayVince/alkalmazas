package com.ble.resistancemeter.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ble.resistancemeter.R
import com.ble.resistancemeter.databinding.ActivityMainBinding
import com.ble.resistancemeter.repository.BleRepository
import com.ble.resistancemeter.service.MeasurementService
import com.ble.resistancemeter.viewmodel.MeasurementViewModel
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MeasurementViewModel by viewModels()
    private lateinit var sharedPreferences: SharedPreferences
    
    private var isServiceRunning = false
    private var isDemoMode = false
    
    private val demoHandler = Handler(Looper.getMainLooper())
    
    private val demoRunnable = object : Runnable {
        override fun run() {
            if (isDemoMode) {
                // Generate random resistance value (500-2000 ohms range)
                val randomValue = Random.nextInt(500, 2000)
                // Send to service
                val intent = Intent(this@MainActivity, MeasurementService::class.java).apply {
                    action = MeasurementService.ACTION_BLE_DATA
                    putExtra("raw_value", randomValue)
                }
                startService(intent)
                demoHandler.postDelayed(this, 1000) // every second
            }
        }
    }
    
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "Permissions are required for the app to work", Toast.LENGTH_LONG).show()
        }
    }
    
    private val aValueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val aValue = intent?.getDoubleExtra(MeasurementService.EXTRA_A_VALUE, 0.0) ?: 0.0
            binding.textCurrentValue.text = String.format("%.1f", aValue)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        sharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        
        // Load demo mode state
        isDemoMode = sharedPreferences.getBoolean("demo_mode", false)
        binding.switchDemoMode.isChecked = isDemoMode
        
        requestPermissions()
        setupObservers()
        setupListeners()
        loadParameters()
        
        // Register broadcast receiver for A value updates
        val filter = IntentFilter(MeasurementService.ACTION_A_UPDATE)
        ContextCompat.registerReceiver(
            this,
            aValueReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopDemoGeneration()
        unregisterReceiver(aValueReceiver)
    }
    
    override fun onResume() {
        super.onResume()
        // Restart demo mode if it was enabled
        if (isDemoMode) {
            startDemoGeneration()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Stop demo mode when paused to save battery
        stopDemoGeneration()
    }
    
    private fun startDemoGeneration() {
        demoHandler.removeCallbacks(demoRunnable)
        demoHandler.post(demoRunnable)
    }
    
    private fun stopDemoGeneration() {
        demoHandler.removeCallbacks(demoRunnable)
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        
        // Location permissions
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        // Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        // Storage permissions (for older Android versions)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
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
            // This is kept for backward compatibility with demo mode in ViewModel
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
            // Save demo mode state
            sharedPreferences.edit().putBoolean("demo_mode", isDemoMode).apply()
            
            // Start or stop demo data generation
            if (isDemoMode) {
                startDemoGeneration()
            } else {
                stopDemoGeneration()
            }
            
            // Also update ViewModel for backward compatibility
            viewModel.toggleDemoMode(isChecked)
        }
    }
    
    private fun startMeasurementService() {
        // Validate and save parameters
        val n = validateAndSaveN()
        val b = validateAndSaveB()
        
        if (n == null || b == null) {
            return
        }
        
        // Start the service
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
        
        // Send initial parameters to service
        sendParametersToService(n, b)
    }
    
    private fun stopMeasurementService() {
        val intent = Intent(this, MeasurementService::class.java).apply {
            action = MeasurementService.ACTION_STOP
        }
        startService(intent)
        
        isServiceRunning = false
        updateUIForRunningState(false)
    }
    
    private fun validateAndSaveN(): Int? {
        val text = binding.editTextN.text.toString()
        val value = text.toIntOrNull()
        
        if (value == null || value !in 1..99) {
            Toast.makeText(this, "N must be between 1 and 99", Toast.LENGTH_SHORT).show()
            return null
        }
        
        sharedPreferences.edit().putInt("n_value", value).apply()
        
        // Send parameter update to service if running
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
            Toast.makeText(this, "B must be between 1 and 999", Toast.LENGTH_SHORT).show()
            return null
        }
        
        sharedPreferences.edit().putInt("b_value", value).apply()
        
        // Send parameter update to service if running
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
            // Don't disable demo mode switch - it should work independently
        } else {
            binding.buttonStartStop.text = getString(R.string.start_measurement)
            binding.editTextN.isEnabled = true
            binding.editTextB.isEnabled = true
        }
    }
}
