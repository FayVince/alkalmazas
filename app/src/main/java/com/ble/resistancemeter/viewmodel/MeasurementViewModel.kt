package com.ble.resistancemeter.viewmodel

import android.app.Application
import android.content.Context
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ble.resistancemeter.data.Measurement
import com.ble.resistancemeter.data.MeasurementData
import com.ble.resistancemeter.data.ParameterChange
import com.ble.resistancemeter.repository.BleRepository
import com.ble.resistancemeter.repository.FileRepository
import com.ble.resistancemeter.service.MeasurementService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class MeasurementViewModel(application: Application) : AndroidViewModel(application) {
    
    private val sharedPreferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val fileRepository = FileRepository(application)
    private val bleRepository = BleRepository(application)
    private val fusedLocationClient: FusedLocationProviderClient = 
        LocationServices.getFusedLocationProviderClient(application)
    
    private val _nValue = MutableLiveData<Int>()
    val nValue: LiveData<Int> = _nValue
    
    private val _bValue = MutableLiveData<Int>()
    val bValue: LiveData<Int> = _bValue
    
    private val _currentAValue = MutableLiveData<Double>()
    val currentAValue: LiveData<Double> = _currentAValue
    
    private val _isRunning = MutableLiveData<Boolean>()
    val isRunning: LiveData<Boolean> = _isRunning
    
    private val _connectionState = MutableLiveData<BleRepository.ConnectionState>()
    val connectionState: LiveData<BleRepository.ConnectionState> = _connectionState
    
    private val _demoMode = MutableLiveData<Boolean>()
    val demoMode: LiveData<Boolean> = _demoMode
    
    private val resistanceHistory = mutableListOf<Int>()
    private var saveJob: Job? = null
    private var demoJob: Job? = null
    
    init {
        loadSettings()
        _isRunning.value = false
        _demoMode.value = true // Start in demo mode by default
        
        viewModelScope.launch {
            bleRepository.connectionState.collect { state ->
                _connectionState.postValue(state)
            }
        }
        
        viewModelScope.launch {
            bleRepository.resistanceValue.collect { value ->
                value?.let { addResistanceValue(it) }
            }
        }
    }
    
    private fun loadSettings() {
        _nValue.value = sharedPreferences.getInt("n_value", 10)
        _bValue.value = sharedPreferences.getInt("b_value", 5)
    }
    
    fun setNValue(value: Int) {
        if (value in 1..99) {
            _nValue.value = value
            sharedPreferences.edit().putInt("n_value", value).apply()
            
            if (_isRunning.value == true) {
                saveParameterChange()
            }
        }
    }
    
    fun setBValue(value: Int) {
        if (value in 1..999) {
            _bValue.value = value
            sharedPreferences.edit().putInt("b_value", value).apply()
            
            if (_isRunning.value == true) {
                saveParameterChange()
                restartSaveTimer()
            }
        }
    }
    
    fun toggleDemoMode(enabled: Boolean) {
        _demoMode.value = enabled
        if (!enabled && _isRunning.value == true) {
            stopDemo()
        }
    }
    
    fun startMeasurement() {
        if (_isRunning.value == true) return
        
        _isRunning.value = true
        resistanceHistory.clear()
        
        fileRepository.createNewFile()
        saveParameterChange()
        
        if (_demoMode.value == true) {
            startDemo()
        }
        
        startSaveTimer()
    }
    
    fun stopMeasurement() {
        _isRunning.value = false
        stopSaveTimer()
        stopDemo()
        resistanceHistory.clear()
    }
    
    private fun startDemo() {
        demoJob = viewModelScope.launch {
            while (isActive) {
                // Generate random resistance value between 500 and 1500
                val randomValue = (500..1500).random()
                addResistanceValue(randomValue)
                delay(1000) // Every second
            }
        }
    }
    
    private fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
    }
    
    private fun addResistanceValue(value: Int) {
        resistanceHistory.add(value)
        
        val n = _nValue.value ?: 10
        if (resistanceHistory.size > n) {
            resistanceHistory.removeAt(0)
        }
        
        calculateAValue()
    }
    
    private fun calculateAValue() {
        val n = _nValue.value ?: 10
        val valuesToAverage = resistanceHistory.takeLast(n)
        
        if (valuesToAverage.isNotEmpty()) {
            val average = valuesToAverage.average()
            _currentAValue.postValue(average)
        }
    }
    
    private fun startSaveTimer() {
        stopSaveTimer()
        
        val b = _bValue.value ?: 5
        saveJob = viewModelScope.launch {
            while (isActive) {
                delay(b * 1000L)
                saveMeasurement()
            }
        }
    }
    
    private fun stopSaveTimer() {
        saveJob?.cancel()
        saveJob = null
    }
    
    private fun restartSaveTimer() {
        stopSaveTimer()
        startSaveTimer()
    }
    
    private fun saveMeasurement() {
        val aValue = _currentAValue.value ?: return
        
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                
                val measurement = Measurement(
                    timestamp = timestamp,
                    value = aValue,
                    latitude = location?.latitude ?: 0.0,
                    longitude = location?.longitude ?: 0.0
                )
                
                fileRepository.addMeasurement(measurement)
            }
        } catch (e: SecurityException) {
            // Location permission not granted
            e.printStackTrace()
        }
    }
    
    private fun saveParameterChange() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        
        val parameterChange = ParameterChange(
            timestamp = timestamp,
            n = _nValue.value ?: 10,
            B = _bValue.value ?: 5
        )
        
        fileRepository.addParameterChange(parameterChange)
    }
    
    fun saveMeasurementData(callback: (String?) -> Unit) {
        viewModelScope.launch {
            val measurements = MeasurementService.getMeasurements().toMutableList()
            if (measurements.isNotEmpty()) {
                val parameterChanges = MeasurementService.getParameterChanges().toMutableList()
                val startTime = MeasurementService.getStartTime()
                
                val measurementData = MeasurementData(
                    startTime = startTime,
                    measurements = measurements,
                    parameterChanges = parameterChanges
                )
                
                val fileName = fileRepository.saveMeasurementData(measurementData)
                callback(fileName)
            } else {
                callback(null)
            }
        }
    }
    
    fun connectToDevice(device: android.bluetooth.BluetoothDevice) {
        bleRepository.connectToDevice(device)
        _demoMode.value = false
    }
    
    fun disconnectDevice() {
        bleRepository.disconnect()
    }
    
    fun getAllMeasurementFiles(): List<File> = fileRepository.getAllMeasurementFiles()
    
    fun loadMeasurementFile(file: File): MeasurementData? = fileRepository.loadFile(file)
    
    fun convertJsonToCsv(jsonFile: File): File? {
        val measurementData = fileRepository.loadFile(jsonFile) ?: return null
        val csvFile = File(jsonFile.parent, jsonFile.name.replace(".json", ".csv"))

        try {
            FileWriter(csvFile).use { writer ->
                writer.append("timestamp,value,latitude,longitude\n")

                measurementData.measurements.forEach { measurement ->
                    writer.append("${measurement.timestamp},${measurement.value},${measurement.latitude},${measurement.longitude}\n")
                }
            }
            return csvFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        stopMeasurement()
        disconnectDevice()
    }
}
