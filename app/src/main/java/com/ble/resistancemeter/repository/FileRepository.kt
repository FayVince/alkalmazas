package com.ble.resistancemeter.repository

import android.content.Context
import android.os.Environment
import com.ble.resistancemeter.data.Measurement
import com.ble.resistancemeter.data.MeasurementData
import com.ble.resistancemeter.data.ParameterChange
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class FileRepository(private val context: Context) {
    
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private var currentData: MeasurementData? = null
    private var currentFile: File? = null
    
    fun createNewFile(): File {
        val dateFormat = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault())
        val currentDate = dateFormat.format(Date())
        val fileName = "${currentDate}_GPS.json"
        
        val documentsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "BLEResistanceMeter"
        )
        
        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }
        
        val file = File(documentsDir, fileName)
        
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        currentData = MeasurementData(startTime = timestampFormat.format(Date()))
        
        saveToFile(file)
        currentFile = file
        
        return file
    }
    
    fun addMeasurement(measurement: Measurement) {
        currentData?.measurements?.add(measurement)
        currentFile?.let { saveToFile(it) }
    }
    
    fun addParameterChange(parameterChange: ParameterChange) {
        currentData?.parameterChanges?.add(parameterChange)
        currentFile?.let { saveToFile(it) }
    }
    
    private fun saveToFile(file: File) {
        try {
            FileWriter(file).use { writer ->
                gson.toJson(currentData, writer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveMeasurementData(measurementData: MeasurementData): String? {
        val dateFormat = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault())
        val currentDate = dateFormat.format(Date())
        val fileName = "${currentDate}_GPS.json"

        val documentsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "BLEResistanceMeter"
        )

        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }

        val file = File(documentsDir, fileName)

        return try {
            FileWriter(file).use { writer ->
                gson.toJson(measurementData, writer)
            }
            fileName
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun loadFile(file: File): MeasurementData? {
        return try {
            val json = file.readText()
            gson.fromJson(json, MeasurementData::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun getAllMeasurementFiles(): List<File> {
        val documentsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "BLEResistanceMeter"
        )
        
        if (!documentsDir.exists()) {
            return emptyList()
        }
        
        return documentsDir.listFiles { file ->
            file.extension == "json" && file.name.endsWith("_GPS.json")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
