package com.verlyn.audio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Ink = Color(0xFF090B10)
private val Panel = Color(0xFF151922)
private val Blue = Color(0xFF4C8DFF)
private val Muted = Color(0xFF9AA4B2)

class MainActivity : ComponentActivity() {
    private val devices = mutableStateListOf<DeviceRow>()
    private var status by mutableStateOf("Ready to discover nearby devices")
    private var scanning by mutableStateOf(false)
    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                loadPairedDevices()
                startScan()
            } else {
                status = "Bluetooth permissions are required to discover devices."
            }
        }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try {
                device.name ?: result.scanRecord?.deviceName ?: "Unknown Bluetooth device"
            } catch (_: SecurityException) {
                "Bluetooth device"
            }
            val address = try { device.address } catch (_: SecurityException) { "Unavailable" }
            val row = DeviceRow(name, address, result.rssi, false)
            runOnUiThread {
                val index = devices.indexOfFirst { it.address == address }
                if (index >= 0) devices[index] = row else devices.add(row)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                scanning = false
                status = "Scan failed (code $errorCode). Try again."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        scanner = adapter?.bluetoothLeScanner

        setContent {
            VerlynTheme {
                VerlynScreen(
                    status = status,
                    scanning = scanning,
                    devices = devices,
                    onScan = { checkPermissionsAndScan() }
                )
            }
        }
        checkPermissionsAndScan()
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissionsAndScan() {
        if (adapter == null) {
            status = "This phone does not report a Bluetooth adapter."
            return
        }
        if (!hasPermissions()) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            permissionLauncher.launch(permissions)
        } else {
            loadPairedDevices()
            startScan()
        }
    }

    @Suppress("MissingPermission")
    private fun loadPairedDevices() {
        if (!hasPermissions()) return
        try {
            adapter?.bondedDevices?.forEach { device ->
                val name = device.name ?: "Paired Bluetooth device"
                val address = device.address
                if (devices.none { it.address == address }) {
                    devices.add(DeviceRow(name, address, null, true))
                }
            }
        } catch (_: SecurityException) {
            status = "Permission is needed to read paired devices."
        }
    }

    @Suppress("MissingPermission")
    private fun startScan() {
        if (!hasPermissions()) return
        if (adapter?.isEnabled != true) {
            status = "Turn Bluetooth on, then tap Scan."
            scanning = false
            return
        }
        try {
            scanner = adapter?.bluetoothLeScanner
            scanner?.startScan(scanCallback)
            scanning = true
            status = "Scanning for nearby Bluetooth Low Energy devices…"
        } catch (_: SecurityException) {
            status = "Bluetooth permission was not granted."
            scanning = false
        } catch (e: Exception) {
            status = "Unable to start scan: ${e.message ?: "unknown error"}"
            scanning = false
        }
    }

    @Suppress("MissingPermission")
    override fun onDestroy() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        super.onDestroy()
    }
}

data class DeviceRow(
    val name: String,
    val address: String,
    val rssi: Int?,
    val paired: Boolean
)

@Composable
private fun VerlynTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blue,
            background = Ink,
            surface = Panel,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
private fun VerlynScreen(
    status: String,
    scanning: Boolean,
    devices: List<DeviceRow>,
    onScan: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
        ) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("VERLYN", fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                    Text("PERSONAL AUDIO COMPANION", color = Muted, fontSize = 10.sp, letterSpacing = 1.6.sp)
                }
                Icon(Icons.Rounded.Bluetooth, contentDescription = "Bluetooth", tint = Blue, modifier = Modifier.size(28.dp))
            }

            Spacer(Modifier.height(30.dp))
            Text("YOUR AUDIO.", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Text("YOUR RULES.", color = Blue, fontSize = 29.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(22.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Headphones, contentDescription = null, tint = Blue, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("JBL TUNE 770NC", fontWeight = FontWeight.Bold)
                            Text("Target device", color = Muted, fontSize = 12.sp)
                        }
                        Surface(color = Color(0xFF173525), shape = RoundedCornerShape(50)) {
                            Text("DISCOVERY", color = Color(0xFF7BE0A5), fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Device controls will appear when supported communication is verified.",
                        color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEARBY DEVICES", fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onScan) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (scanning) "SCAN AGAIN" else "SCAN")
                }
            }
            Text(status, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))

            if (devices.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("No devices discovered yet.", color = Muted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(devices, key = { it.address }) { device ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            shape = RoundedCornerShape(15.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Bluetooth, contentDescription = null, tint = Blue)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(if (device.paired) "Paired device" else "Nearby · ${device.rssi ?: "?"} dBm",
                                        color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
            Text("VERLYN  •  BUILD 0.1.0", color = Color(0xFF586171), fontSize = 9.sp,
                letterSpacing = 1.4.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 14.dp))
        }
    }
}
