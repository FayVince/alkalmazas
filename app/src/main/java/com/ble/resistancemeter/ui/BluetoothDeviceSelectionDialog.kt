package com.ble.resistancemeter.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.ble.resistancemeter.R
import com.ble.resistancemeter.repository.BleRepository

class BluetoothDeviceSelectionDialog(
    private val context: Context,
    private val bleRepository: BleRepository,
    private val onDeviceSelected: (BluetoothDevice) -> Unit
) {
    
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var adapter: DeviceAdapter? = null
    private var dialog: AlertDialog? = null
    
    @SuppressLint("MissingPermission")
    fun show() {
        // Check if Bluetooth is enabled
        if (!bleRepository.isBluetoothEnabled()) {
            AlertDialog.Builder(context)
                .setTitle(R.string.select_bluetooth_device)
                .setMessage(R.string.bluetooth_disabled)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        
        // Check permissions
        if (!hasBluetoothPermissions()) {
            AlertDialog.Builder(context)
                .setTitle(R.string.select_bluetooth_device)
                .setMessage(R.string.permissions_required)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        
        discoveredDevices.clear()
        adapter = DeviceAdapter(context, discoveredDevices)
        
        val builder = AlertDialog.Builder(context)
        builder.setTitle(R.string.select_bluetooth_device)
        builder.setAdapter(adapter) { _, position ->
            val device = discoveredDevices[position]
            bleRepository.stopScan()
            onDeviceSelected(device)
        }
        builder.setNegativeButton(android.R.string.cancel) { _, _ ->
            bleRepository.stopScan()
        }
        builder.setOnDismissListener {
            bleRepository.stopScan()
        }
        
        dialog = builder.create()
        dialog?.show()
        
        // Start scanning for devices
        bleRepository.startScan { device ->
            // Only add if not already in the list
            if (!discoveredDevices.any { it.address == device.address }) {
                discoveredDevices.add(device)
                adapter?.notifyDataSetChanged()
            }
        }
        
        // Set a timeout message if no devices found after some time
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (discoveredDevices.isEmpty() && dialog?.isShowing == true) {
                dialog?.setTitle(context.getString(R.string.no_devices_found))
            }
        }, 5000)
    }
    
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == 
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == 
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH) == 
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADMIN) == 
                PackageManager.PERMISSION_GRANTED
        }
    }
    
    @SuppressLint("MissingPermission")
    private inner class DeviceAdapter(
        context: Context, 
        devices: List<BluetoothDevice>
    ) : ArrayAdapter<BluetoothDevice>(context, android.R.layout.simple_list_item_2, devices) {
        
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(
                android.R.layout.simple_list_item_2, parent, false
            )
            
            val device = getItem(position)
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)
            
            device?.let {
                text1.text = it.name ?: context.getString(R.string.no_devices_found)
                text2.text = it.address
            }
            
            return view
        }
    }
}