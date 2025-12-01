package com.ble.resistancemeter.data

data class Measurement(
    val timestamp: String,
    val value: Double,
    val latitude: Double,
    val longitude: Double
)
