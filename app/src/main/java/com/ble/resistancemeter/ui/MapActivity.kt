package com.ble.resistancemeter.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.ble.resistancemeter.R
import com.ble.resistancemeter.data.Measurement
import com.ble.resistancemeter.data.MeasurementData
import com.ble.resistancemeter.databinding.ActivityMapBinding
import com.ble.resistancemeter.viewmodel.MeasurementViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import java.io.File

class MapActivity : AppCompatActivity(), OnMapReadyCallback {
    
    private lateinit var binding: ActivityMapBinding
    private val viewModel: MeasurementViewModel by viewModels()
    private var googleMap: GoogleMap? = null
    private var currentData: MeasurementData? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.resistance_map)
        
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        
        binding.buttonLoadFile.setOnClickListener {
            showFileSelectionDialog()
        }
    }
    
    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        
        // Try to load and display the most recent file
        val files = viewModel.getAllMeasurementFiles()
        if (files.isNotEmpty()) {
            loadAndDisplayFile(files.first())
        }
    }
    
    private fun showFileSelectionDialog() {
        val files = viewModel.getAllMeasurementFiles()
        
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No Files")
                .setMessage("No measurement files found")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        val fileNames = files.map { it.name }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_file))
            .setItems(fileNames) { _, which ->
                loadAndDisplayFile(files[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun loadAndDisplayFile(file: File) {
        val data = viewModel.loadMeasurementFile(file)
        if (data != null && data.measurements.isNotEmpty()) {
            currentData = data
            displayMeasurements(data.measurements)
        }
    }
    
    private fun displayMeasurements(measurements: List<Measurement>) {
        googleMap?.clear()
        
        if (measurements.isEmpty()) return
        
        // Find min and max values for color mapping
        val values = measurements.map { it.value }
        val minValue = values.minOrNull() ?: 0.0
        val maxValue = values.maxOrNull() ?: 1000.0
        
        val boundsBuilder = LatLngBounds.Builder()
        
        measurements.forEach { measurement ->
            val position = LatLng(measurement.latitude, measurement.longitude)
            
            // Calculate color based on value (heatmap style)
            val normalizedValue = if (maxValue > minValue) {
                (measurement.value - minValue) / (maxValue - minValue)
            } else {
                0.5
            }
            
            val color = getHeatmapColor(normalizedValue.toFloat())
            
            // Add circle marker
            googleMap?.addCircle(
                CircleOptions()
                    .center(position)
                    .radius(5.0) // 5 meters
                    .fillColor(color)
                    .strokeColor(Color.WHITE)
                    .strokeWidth(2f)
            )
            
            boundsBuilder.include(position)
        }
        
        // Move camera to show all points
        try {
            val bounds = boundsBuilder.build()
            val padding = 100 // pixels
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
        } catch (e: Exception) {
            // If only one point or error, just center on first point
            val firstPoint = LatLng(measurements.first().latitude, measurements.first().longitude)
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(firstPoint, 15f))
        }
    }
    
    private fun getHeatmapColor(value: Float): Int {
        // value: 0.0 (low) to 1.0 (high)
        // Blue (low) -> Green -> Yellow -> Red (high)
        
        return when {
            value < 0.25f -> {
                // Blue to Cyan
                val ratio = value / 0.25f
                Color.rgb(0, (ratio * 255).toInt(), 255)
            }
            value < 0.5f -> {
                // Cyan to Green
                val ratio = (value - 0.25f) / 0.25f
                Color.rgb(0, 255, ((1 - ratio) * 255).toInt())
            }
            value < 0.75f -> {
                // Green to Yellow
                val ratio = (value - 0.5f) / 0.25f
                Color.rgb((ratio * 255).toInt(), 255, 0)
            }
            else -> {
                // Yellow to Red
                val ratio = (value - 0.75f) / 0.25f
                Color.rgb(255, ((1 - ratio) * 255).toInt(), 0)
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
