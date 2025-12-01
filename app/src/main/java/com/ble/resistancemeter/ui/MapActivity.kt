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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

class MapActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMapBinding
    private val viewModel: MeasurementViewModel by viewModels()
    private lateinit var mapView: MapView
    private var currentData: MeasurementData? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // osmdroid configuration
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.resistance_map)
        
        // Initialize map view
        mapView = binding.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        
        val mapController = mapView.controller
        mapController.setZoom(15.0)
        
        // Default center (Budapest)
        val startPoint = GeoPoint(47.4979, 19.0402)
        mapController.setCenter(startPoint)
        
        // Try to load and display the most recent file
        val files = viewModel.getAllMeasurementFiles()
        if (files.isNotEmpty()) {
            loadAndDisplayFile(files.first())
        }
        
        binding.buttonLoadFile.setOnClickListener {
            showFileSelectionDialog()
        }
    }
    
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
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
        mapView.overlays.clear()
        
        if (measurements.isEmpty()) return
        
        // Find min and max values for color mapping
        val values = measurements.map { it.value }
        val minValue = values.minOrNull() ?: 0.0
        val maxValue = values.maxOrNull() ?: 1000.0
        
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        
        measurements.forEach { measurement ->
            val position = GeoPoint(measurement.latitude, measurement.longitude)
            
            // Calculate color based on value (heatmap style)
            val normalizedValue = if (maxValue > minValue) {
                (measurement.value - minValue) / (maxValue - minValue)
            } else {
                0.5
            }
            
            val color = getHeatmapColor(normalizedValue.toFloat())
            
            // Add circle marker using a Marker with custom appearance
            val marker = Marker(mapView)
            marker.position = position
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.title = "Value: ${String.format("%.2f", measurement.value)}"
            
            // Create a colored circle icon
            marker.icon = createCircleDrawable(color)
            
            mapView.overlays.add(marker)
            
            // Track bounds
            minLat = minOf(minLat, measurement.latitude)
            maxLat = maxOf(maxLat, measurement.latitude)
            minLon = minOf(minLon, measurement.longitude)
            maxLon = maxOf(maxLon, measurement.longitude)
        }
        
        mapView.invalidate()
        
        // Move camera to show all points
        if (measurements.size == 1) {
            // Single point - just center on it
            val point = GeoPoint(measurements.first().latitude, measurements.first().longitude)
            mapView.controller.setCenter(point)
            mapView.controller.setZoom(15.0)
        } else {
            // Multiple points - fit to bounds
            val centerLat = (minLat + maxLat) / 2
            val centerLon = (minLon + maxLon) / 2
            mapView.controller.setCenter(GeoPoint(centerLat, centerLon))
            
            // Calculate appropriate zoom level based on bounds
            val latDiff = maxLat - minLat
            val lonDiff = maxLon - minLon
            val maxDiff = maxOf(latDiff, lonDiff)
            
            val zoom = when {
                maxDiff > 0.1 -> 11.0
                maxDiff > 0.05 -> 12.0
                maxDiff > 0.02 -> 13.0
                maxDiff > 0.01 -> 14.0
                else -> 15.0
            }
            mapView.controller.setZoom(zoom)
        }
    }
    
    private fun createCircleDrawable(color: Int): android.graphics.drawable.Drawable {
        val size = 30
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        
        // Draw filled circle
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            this.color = color
        }
        val radius = size / 2f - 2
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)
        
        // Draw white border
        paint.apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            this.color = Color.WHITE
        }
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)
        
        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
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
