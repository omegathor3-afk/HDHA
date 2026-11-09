package com.omega.hdha

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var usbManager: UsbManager
    private var devices by mutableStateOf(emptyList<UsbDriveCandidate>())
    private var inquiryResults by mutableStateOf<Map<String, UsbBotTransport.InquiryResult>>(emptyMap())
    private var ataResults by mutableStateOf<Map<String, UsbBotTransport.AtaIdentifyResult>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        setContent {
            MaterialTheme {
                HdhaScreen(
                    devices = devices,
                    inquiryResults = inquiryResults,
                    ataResults = ataResults,
                    hasPermission = { name -> usbManager.deviceList[name]?.let(usbManager::hasPermission) == true },
                    onRefresh = ::refreshDevices,
                    onRequestPermission = ::requestUsbPermission,
                    onRunInquiry = ::runInquiry,
                    onRunAtaIdentify = ::runAtaIdentify
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::usbManager.isInitialized) refreshDevices()
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

    private fun runAtaIdentify(deviceName: String) {
        val device = usbManager.deviceList[deviceName] ?: return
        ataResults = ataResults - deviceName
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { UsbBotTransport.identifyAta(usbManager, device) }
            ataResults = ataResults + (deviceName to result)
        }
    }

    private fun runInquiry(deviceName: String) {
        val device = usbManager.deviceList[deviceName] ?: return
        inquiryResults = inquiryResults - deviceName
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                UsbBotTransport.inquiry(usbManager, device)
            }
            inquiryResults = inquiryResults + (deviceName to result)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun HdhaScreen(
    devices: List<UsbDriveCandidate>,
    inquiryResults: Map<String, UsbBotTransport.InquiryResult>,
    ataResults: Map<String, UsbBotTransport.AtaIdentifyResult>,
    hasPermission: (String) -> Boolean,
    onRefresh: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onRunInquiry: (String) -> Unit,
    onRunAtaIdentify: (String) -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text("HDHA · Drive Inspector") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("USB device discovery", style = MaterialTheme.typography.headlineSmall)
            Text("SCSI INQUIRY identifies the USB device. SAT probing sends ATA IDENTIFY DEVICE only (read-only); SMART attributes are not read yet.")
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
                                    Text("Interface ${intf.id}: class ${intf.interfaceClass}, subclass ${intf.subclass}, protocol 0x${intf.protocol.toString(16)}")
                                    val profile = UsbMassStorageProfile.inspect(intf)
                                    if (profile != null) {
                                        Text("Transport: ${profile.transport}")
                                        Text("Bulk IN: ${profile.hasBulkIn} · Bulk OUT: ${profile.hasBulkOut}")
                                        Text(
                                            if (profile.canAttemptBulkOnlyTransport) "BOT descriptor check: suitable for SCSI INQUIRY"
                                            else "BOT descriptor check: not suitable"
                                        )
                                        Text(profile.limitation, style = MaterialTheme.typography.bodySmall)
                                    }
                                    intf.endpoints.forEach { endpoint ->
                                        Text("  ${endpoint.direction} ${endpoint.type} · endpoint 0x${endpoint.address.toString(16)} · packet ${endpoint.maxPacketSize}")
                                    }
                                }
                                val result = inquiryResults[device.deviceName]
                                if (result != null) {
                                    Text(
                                        if (result.success) "SCSI INQUIRY: device identified"
                                        else "SCSI INQUIRY: unavailable",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    if (result.success) {
                                        Text("Vendor: ${result.vendor.ifBlank { "Unknown" }}")
                                        Text("Product: ${result.product.ifBlank { "Unknown" }}")
                                        Text("Revision: ${result.revision.ifBlank { "Unknown" }}")
                                        Text("Peripheral type: ${result.peripheralDeviceType}")
                                        Text("Removable: ${result.removable}")
                                    }
                                    Text(result.message, style = MaterialTheme.typography.bodySmall)
                                }
                                val ata = ataResults[device.deviceName]
                                if (ata != null) {
                                    Text(if (ata.success) "SAT / ATA IDENTIFY: succeeded" else "SAT / ATA IDENTIFY: unavailable")
                                    if (ata.success) {
                                        Text("Drive model: ${ata.model.ifBlank { "Unknown" }}")
                                        Text("Serial number: ${ata.serialNumber.ifBlank { "Unknown" }}")
                                        Text("Firmware: ${ata.firmware.ifBlank { "Unknown" }}")
                                        Text("SMART supported: ${ata.smartSupported} · enabled: ${ata.smartEnabled}")
                                        Text("48-bit LBA: ${ata.lba48Supported}")
                                        if (ata.capacityBytes > 0) Text("Reported capacity: ${"%.2f".format(ata.capacityBytes / 1_000_000_000.0)} GB")
                                        if (ata.nonRotatingMedia) Text("Media: non-rotating (commonly SSD)")
                                        else ata.rotationRateRpm?.let { Text("Rotation rate: $it RPM") }
                                    }
                                    Text(ata.message, style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onRequestPermission(device.deviceName) },
                                        enabled = !hasPermission(device.deviceName)
                                    ) { Text(if (hasPermission(device.deviceName)) "Permission granted" else "Request USB access") }
                                    Button(
                                        onClick = { onRunInquiry(device.deviceName) },
                                        enabled = hasPermission(device.deviceName) && device.interfaces.any {
                                            UsbMassStorageProfile.inspect(it)?.canAttemptBulkOnlyTransport == true
                                        }
                                    ) { Text("Read device identity") }
                                    Button(onClick = { onRunAtaIdentify(device.deviceName) }, enabled = hasPermission(device.deviceName) && device.interfaces.any { UsbMassStorageProfile.inspect(it)?.canAttemptBulkOnlyTransport == true }) { Text("Probe SAT / Identify ATA") }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Read-only commands: SCSI INQUIRY and ATA IDENTIFY DEVICE. No SMART data commands, writes, formatting, repair, or sector tests are performed.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
