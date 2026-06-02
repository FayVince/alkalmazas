package com.ble.resistancemeter.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ble.resistancemeter.R
import com.ble.resistancemeter.data.Measurement
import com.ble.resistancemeter.databinding.ActivityMapBinding
import com.ble.resistancemeter.service.MeasurementService
import com.ble.resistancemeter.viewmodel.MeasurementViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private val viewModel: MeasurementViewModel by viewModels()
    private lateinit var mapView: MapView
    private var currentFile: File? = null
    private var isShowingLiveUpdates = false

    private val measurementUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isShowingLiveUpdates) {
                // Új mérés érkezett: frissítjük a rajzot, de NEM mozgatjuk el a kamerát
                updateLiveMeasurements(shouldCenter = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid configuration
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.resistance_map)

        // Initialize map view
        mapView = binding.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)

        val mapController = mapView.controller
        mapController.setZoom(15.0)

        // Alapértelmezett közép (Budapest)
        val startPoint = GeoPoint(47.4979, 19.0402)
        mapController.setCenter(startPoint)

        // Ellenőrizzük, hogy fut-e mérés az élő frissítésekhez
        if (MeasurementService.isRunning()) {
            isShowingLiveUpdates = true
            
            // Amikor a térkép gombra kattintva megnyílik, a legutóbbi pont legyen a közepén (egyszeri alkalom)
            mapView.post {
                val measurements = MeasurementService.getMeasurements().toList()
                if (measurements.isNotEmpty()) {
                    displayMeasurements(measurements, autoCenter = false)
                    val last = measurements.last()
                    val point = GeoPoint(last.latitude, last.longitude)
                    mapView.controller.setCenter(point)
                    mapView.controller.setZoom(18.0)
                }
            }
        } else {
            // Ha nem fut mérés, betöltjük a legutóbbi fájlt
            val files = viewModel.getAllMeasurementFiles()
            if (files.isNotEmpty()) {
                loadAndDisplayFile(files.first())
            }
        }

        binding.fabLoadFile.setOnClickListener {
            showFileSelectionDialog()
        }

        binding.fabShareFile.setOnClickListener {
            shareCurrentFileAsCsv()
        }

        val filter = IntentFilter(MeasurementService.ACTION_NEW_MEASUREMENT)
        ContextCompat.registerReceiver(this, measurementUpdateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun updateLiveMeasurements(shouldCenter: Boolean = false) {
        val measurements = MeasurementService.getMeasurements().toList()
        if (measurements.isNotEmpty()) {
            displayMeasurements(measurements, autoCenter = false)
            if (shouldCenter) {
                val last = measurements.last()
                val point = GeoPoint(last.latitude, last.longitude)
                mapView.controller.animateTo(point)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (MeasurementService.isRunning()) {
            isShowingLiveUpdates = true
            // Visszatéréskor csak az adatokat frissítjük, a kamera marad ahol a felhasználó hagyta
            updateLiveMeasurements(shouldCenter = false)
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(measurementUpdateReceiver)
        } catch (e: Exception) {}
        mapView.onDetach()
    }

    private fun showFileSelectionDialog() {
        val files = viewModel.getAllMeasurementFiles()

        if (files.isEmpty() && !MeasurementService.isRunning()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.no_files_title))
                .setMessage(getString(R.string.no_files_message))
                .setPositiveButton(getString(R.string.ok), null)
                .show()
            return
        }

        val fileList = mutableListOf<String>()
        if (MeasurementService.isRunning()) {
            fileList.add(getString(R.string.live_measurement))
        }
        fileList.addAll(files.map { it.name })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_file))
            .setItems(fileList.toTypedArray()) { _, which ->
                if (MeasurementService.isRunning() && which == 0) {
                    isShowingLiveUpdates = true
                    currentFile = null
                    // Ha a választóból az élő mérést választja, centerezzünk egyszer
                    updateLiveMeasurements(shouldCenter = true)
                    mapView.controller.setZoom(18.0)
                } else {
                    isShowingLiveUpdates = false
                    val fileIndex = if (MeasurementService.isRunning()) which - 1 else which
                    loadAndDisplayFile(files[fileIndex])
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun loadAndDisplayFile(file: File) {
        val data = viewModel.loadMeasurementFile(file)
        if (data != null && data.measurements.isNotEmpty()) {
            currentFile = file
            displayMeasurements(data.measurements, autoCenter = true)
        }
    }

    private fun shareCurrentFileAsCsv() {
        if (isShowingLiveUpdates) return
        
        currentFile?.let {
            val csvFile = viewModel.convertJsonToCsv(it)
            csvFile?.let {
                val uri = FileProvider.getUriForFile(this, "${packageName}.provider", it)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_csv_file)))
            }
        }
    }

    private fun displayMeasurements(measurements: List<Measurement>, autoCenter: Boolean) {
        mapView.overlays.clear()

        if (measurements.isEmpty()) return

        // Min és max értékek a színkódoláshoz
        var minValue = Double.MAX_VALUE
        var maxValue = -Double.MAX_VALUE

        measurements.forEach { measurement ->
            minValue = minOf(minValue, measurement.value)
            maxValue = maxOf(maxValue, measurement.value)
        }

        // Vonalláncok rajzolása
        if (measurements.size > 1) {
            for (i in 0 until measurements.size - 1) {
                val p1 = measurements[i]
                val p2 = measurements[i + 1]

                val normalizedValue = if (maxValue > minValue) {
                    (p1.value - minValue) / (maxValue - minValue)
                } else {
                    0.5
                }
                val color = getHeatmapColor(normalizedValue.toFloat())

                val line = Polyline()
                line.addPoint(GeoPoint(p1.latitude, p1.longitude))
                line.addPoint(GeoPoint(p2.latitude, p2.longitude))
                line.color = color
                line.outlinePaint.strokeWidth = 10f
                mapView.overlays.add(line)
            }
        }

        // Mérési pontok (markerek) rajzolása
        measurements.forEach { measurement ->
            val position = GeoPoint(measurement.latitude, measurement.longitude)

            val normalizedValue = if (maxValue > minValue) {
                (measurement.value - minValue) / (maxValue - minValue)
            } else {
                0.5
            }

            val color = getHeatmapColor(normalizedValue.toFloat())

            val marker = Marker(mapView)
            marker.position = position
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.title = "Value: ${String.format("%.2f", measurement.value)} Ω"
            marker.icon = createCircleDrawable(color)
            
            // Megjelenítjük az infó ablakot kattintásra, de nem mozgatjuk el a térképet
            marker.setOnMarkerClickListener { m, _ ->
                m.showInfoWindow()
                true
            }

            mapView.overlays.add(marker)
        }
        
        setupLegend(minValue, maxValue)

        if (autoCenter) {
            centerOnMeasurements(measurements)
        }

        mapView.invalidate()
    }

    private fun centerOnMeasurements(measurements: List<Measurement>) {
        if (measurements.isEmpty()) return

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        measurements.forEach { measurement ->
            minLat = minOf(minLat, measurement.latitude)
            maxLat = maxOf(maxLat, measurement.latitude)
            minLon = minOf(minLon, measurement.longitude)
            maxLon = maxOf(maxLon, measurement.longitude)
        }

        if (measurements.size == 1) {
            val point = GeoPoint(measurements.first().latitude, measurements.first().longitude)
            mapView.controller.setCenter(point)
            mapView.controller.setZoom(18.0)
        } else {
            val centerLat = (minLat + maxLat) / 2
            val centerLon = (minLon + maxLon) / 2
            mapView.controller.setCenter(GeoPoint(centerLat, centerLon))

            val latDiff = maxLat - minLat
            val lonDiff = maxLon - minLon
            val maxDiff = maxOf(latDiff, lonDiff)

            val zoom = when {
                maxDiff > 0.1 -> 11.0
                maxDiff > 0.05 -> 12.0
                maxDiff > 0.02 -> 13.0
                maxDiff > 0.01 -> 14.0
                else -> 18.0
            }
            mapView.controller.setZoom(zoom)
        }
    }

    private fun setupLegend(minValue: Double, maxValue: Double) {
        val width = 200
        val height = 20
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        for (i in 0 until width) {
            val color = getHeatmapColor(i.toFloat() / width.toFloat())
            val paint = android.graphics.Paint().apply { this.color = color }
            canvas.drawRect(i.toFloat(), 0f, (i + 1).toFloat(), height.toFloat(), paint)
        }

        binding.legendGradient.setImageBitmap(bitmap)
        binding.legendMinText.text = String.format("%.1f Ω", minValue)
        binding.legendMaxText.text = String.format("%.1f Ω", maxValue)
    }

    private fun createCircleDrawable(color: Int): android.graphics.drawable.Drawable {
        val size = 30
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            this.color = color
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    private fun getHeatmapColor(value: Float): Int {
        return when {
            value < 0.25f -> {
                val ratio = value / 0.25f
                Color.rgb(0, (ratio * 255).toInt(), 255)
            }
            value < 0.5f -> {
                val ratio = (value - 0.25f) / 0.25f
                Color.rgb(0, 255, ((1 - ratio) * 255).toInt())
            }
            value < 0.75f -> {
                val ratio = (value - 0.5f) / 0.25f
                Color.rgb((ratio * 255).toInt(), 255, 0)
            }
            else -> {
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
