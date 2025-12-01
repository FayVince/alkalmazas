package com.ble.resistancemeter.data

data class MeasurementData(
    val startTime: String,
    val measurements: MutableList<Measurement> = mutableListOf(),
    val parameterChanges: MutableList<ParameterChange> = mutableListOf()
)
