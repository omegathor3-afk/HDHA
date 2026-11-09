package com.omega.hdha

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private lateinit var usbManager: UsbManager
    private var devices by mutableStateOf(emptyList<UsbDriveCandidate>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        refreshDevices()
        setContent {
            MaterialTheme {
                HdhaScreen(
                    devices = devices,
                    hasPermission = { name -> usbManager.deviceList[name]?.let(usbManager::hasPermission) == true },
                    onRefresh = ::refreshDevices,
                    onRequestPermission = ::requestUsbPermission
                )
            }
        }
    }

    private fun refreshDevices() {
        devices = UsbDeviceInspector.discover(usbManager)
    }

    private fun requestUsbPermission(deviceName: String) {
        val device: UsbDevice = usbManager.deviceList[deviceName] ?: return
        val intent = Intent(this, UsbPermissionReceiver::class.java).apply {
            action = UsbPermissionReceiver.ACTION_USB_PERMISSION
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this, device.deviceId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun HdhaScreen(
    devices: List<UsbDriveCandidate>,
    hasPermission: (String) -> Boolean,
    onRefresh: () -> Unit,
    onRequestPermission: (String) -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text("HDHA · Drive Inspector") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("USB device discovery", style = MaterialTheme.typography.headlineSmall)
            Text("This build detects connected USB devices only. SMART health data is not available yet.")
            Button(onClick = onRefresh) { Text("Refresh connected devices") }
            if (devices.isEmpty()) {
                Text("No USB devices detected. Connect a drive using OTG, then refresh.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(devices, key = { it.deviceName }) { device ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(device.product, style = MaterialTheme.typography.titleMedium)
                                Text(device.manufacturer)
                                Text("VID: %04X · PID: %04X".format(device.vendorId, device.productId))
                                Text(if (device.hasMassStorageInterface) "USB Mass Storage interface detected" else "Not identified as USB Mass Storage")
                                Text("Interfaces: ${device.interfaces.size}")
                                device.interfaces.forEach { intf ->
                                    Text("Interface ${intf.id}: class ${intf.interfaceClass}, subclass ${intf.subclass}, protocol ${intf.protocol}")
                                    intf.endpoints.forEach { endpoint ->
                                        Text("  ${endpoint.direction} ${endpoint.type} · endpoint 0x${endpoint.address.toString(16)} · packet ${endpoint.maxPacketSize}")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onRequestPermission(device.deviceName) },
                                        enabled = !hasPermission(device.deviceName)
                                    ) { Text(if (hasPermission(device.deviceName)) "Permission granted" else "Request USB access") }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Safety: no writes, formatting, repair, or sector tests are performed.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
