package com.ble.resistancemeter.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ble.resistancemeter.R
import com.ble.resistancemeter.databinding.ActivityMainBinding
import com.ble.resistancemeter.repository.BleRepository
import com.ble.resistancemeter.viewmodel.MeasurementViewModel

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MeasurementViewModel by viewModels()
    
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "Permissions are required for the app to work", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        requestPermissions()
        setupObservers()
        setupListeners()
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
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    private fun setupObservers() {
        viewModel.currentAValue.observe(this) { value ->
            binding.textCurrentValue.text = String.format("%.1f", value)
        }
        
        viewModel.nValue.observe(this) { value ->
            binding.textNValue.text = value.toString()
            binding.seekBarN.progress = value - 1
        }
        
        viewModel.bValue.observe(this) { value ->
            binding.textBValue.text = value.toString()
            binding.seekBarB.progress = value - 1
        }
        
        viewModel.isRunning.observe(this) { running ->
            if (running) {
                binding.buttonStartStop.text = getString(R.string.stop_measurement)
                binding.seekBarN.isEnabled = false
                binding.seekBarB.isEnabled = false
                binding.switchDemoMode.isEnabled = false
            } else {
                binding.buttonStartStop.text = getString(R.string.start_measurement)
                binding.seekBarN.isEnabled = true
                binding.seekBarB.isEnabled = true
                binding.switchDemoMode.isEnabled = true
            }
        }
        
        viewModel.connectionState.observe(this) { state ->
            when (state) {
                BleRepository.ConnectionState.DISCONNECTED -> {
                    binding.textConnectionStatus.text = getString(R.string.disconnected)
                    binding.textConnectionStatus.setTextColor(getColor(R.color.red))
                }
                BleRepository.ConnectionState.CONNECTING -> {
                    binding.textConnectionStatus.text = getString(R.string.connecting)
                    binding.textConnectionStatus.setTextColor(getColor(R.color.orange))
                }
                BleRepository.ConnectionState.CONNECTED -> {
                    binding.textConnectionStatus.text = getString(R.string.connected)
                    binding.textConnectionStatus.setTextColor(getColor(R.color.green))
                }
            }
        }
        
        viewModel.demoMode.observe(this) { enabled ->
            binding.switchDemoMode.isChecked = enabled
        }
    }
    
    private fun setupListeners() {
        binding.seekBarN.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.setNValue(progress + 1)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        binding.seekBarB.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.setBValue(progress + 1)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        binding.buttonStartStop.setOnClickListener {
            if (viewModel.isRunning.value == true) {
                viewModel.stopMeasurement()
            } else {
                viewModel.startMeasurement()
            }
        }
        
        binding.buttonViewMap.setOnClickListener {
            val intent = Intent(this, MapActivity::class.java)
            startActivity(intent)
        }
        
        binding.switchDemoMode.setOnCheckedChangeListener { _, isChecked ->
            viewModel.toggleDemoMode(isChecked)
        }
    }
}
