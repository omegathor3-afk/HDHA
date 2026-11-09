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
    private var smartResults by mutableStateOf<Map<String, UsbBotTransport.SmartResult>>(emptyMap())
    private lateinit var historyStore: SmartHistoryStore
    private var historySamples by mutableStateOf(emptyList<SmartSample>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        historyStore = SmartHistoryStore(applicationContext)
        historySamples = historyStore.readAll()
        setContent {
            MaterialTheme {
                HdhaScreen(
                    devices = devices,
                    inquiryResults = inquiryResults,
                    ataResults = ataResults,
                    smartResults = smartResults,
                    historySamples = historySamples,
                    hasPermission = { name -> usbManager.deviceList[name]?.let(usbManager::hasPermission) == true },
                    onRefresh = ::refreshDevices,
                    onRequestPermission = ::requestUsbPermission,
                    onRunInquiry = ::runInquiry,
                    onRunAtaIdentify = ::runAtaIdentify,
                    onRunSmart = ::runSmart,
                    onExportReport = ::exportReport
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

    private fun runInquiry(deviceName: String) {
        val device = usbManager.deviceList[deviceName] ?: return
        inquiryResults = inquiryResults - deviceName
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { UsbBotTransport.inquiry(usbManager, device) }
            inquiryResults = inquiryResults + (deviceName to result)
        }
    }

    private fun runAtaIdentify(deviceName: String) {
        val device = usbManager.deviceList[deviceName] ?: return
        ataResults = ataResults - deviceName
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { UsbBotTransport.identifyAta(usbManager, device) }
            ataResults = ataResults + (deviceName to result)
        }
    }
    private fun runSmart(deviceName: String) {
        val device = usbManager.deviceList[deviceName] ?: return
        smartResults = smartResults - deviceName
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { UsbBotTransport.readSmart(usbManager, device) }
            smartResults = smartResults + (deviceName to result)
            if (result.success) {
                val candidate = devices.firstOrNull { it.deviceName == deviceName }
                val identify = ataResults[deviceName]
                val vendor = inquiryResults[deviceName]?.vendor.orEmpty().ifBlank { candidate?.manufacturer.orEmpty() }
                val model = identify?.model.orEmpty().ifBlank { candidate?.product.orEmpty() }
                val serial = identify?.serialNumber.orEmpty()
                val key = serial.ifBlank { (candidate?.vendorId.toString() + ":" + candidate?.productId + ":" + model) }
                val profile = SmartAttributeProfiles.resolve(vendor, model)
                val sample = SmartSample(System.currentTimeMillis(), key, vendor, model, serial, profile.key,
                    result.thresholdExceeded, result.attributes, result.thresholds)
                historySamples = withContext(Dispatchers.IO) { historyStore.append(sample) }
            }
        }
    }

    private fun exportReport() {
        val report = historyStore.report(historySamples)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "HDHA SMART drive report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "Export SMART report"))
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun HdhaScreen(
    devices: List<UsbDriveCandidate>,
    inquiryResults: Map<String, UsbBotTransport.InquiryResult>,
    ataResults: Map<String, UsbBotTransport.AtaIdentifyResult>,
    smartResults: Map<String, UsbBotTransport.SmartResult>,
    historySamples: List<SmartSample>,
    hasPermission: (String) -> Boolean,
    onRefresh: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onRunInquiry: (String) -> Unit,
    onRunAtaIdentify: (String) -> Unit,
    onRunSmart: (String) -> Unit,
    onExportReport: () -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text("HDHA · Drive Inspector") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("USB device discovery", style = MaterialTheme.typography.headlineSmall)
            Text("SCSI INQUIRY and SAT ATA IDENTIFY are read-only. SMART reads run only when the drive reports SMART supported and enabled.")
            Button(onClick = onRefresh) { Text("Refresh connected devices") }
            Text("Saved SMART samples: ${historySamples.size}")
            Button(onClick = onExportReport, enabled = historySamples.isNotEmpty()) { Text("Export SMART report") }
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
                                            if (profile.canAttemptBulkOnlyTransport) "BOT descriptor check: suitable for read-only commands"
                                            else "BOT descriptor check: not suitable"
                                        )
                                        Text(profile.limitation, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                val inquiry = inquiryResults[device.deviceName]
                                if (inquiry != null) {
                                    Text(if (inquiry.success) "SCSI INQUIRY: identified" else "SCSI INQUIRY: unavailable")
                                    if (inquiry.success) {
                                        Text("Vendor: ${inquiry.vendor.ifBlank { "Unknown" }}")
                                        Text("Product: ${inquiry.product.ifBlank { "Unknown" }}")
                                        Text("Revision: ${inquiry.revision.ifBlank { "Unknown" }}")
                                        Text("Peripheral type: ${inquiry.peripheralDeviceType}")
                                        Text("Removable: ${inquiry.removable}")
                                    }
                                    Text(inquiry.message, style = MaterialTheme.typography.bodySmall)
                                }
                                val ata = ataResults[device.deviceName]
                                if (ata != null) {
                                    Text(
                                        if (ata.success) "SAT: confirmed" else "SAT: not confirmed",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    if (ata.success) {
                                        Text("Model: ${ata.model.ifBlank { "Unknown" }}")
                                        Text("Serial: ${ata.serialNumber.ifBlank { "Unknown" }}")
                                        Text("Firmware: ${ata.firmware.ifBlank { "Unknown" }}")
                                        Text("SMART supported: ${ata.smartSupported} · enabled: ${ata.smartEnabled}")
                                        Text("48-bit LBA: ${ata.lba48Supported}")
                                        if (ata.capacityBytes > 0L) {
                                            Text("Reported capacity: ${"%.2f".format(ata.capacityBytes / 1_000_000_000.0)} GB")
                                        }
                                        if (ata.nonRotatingMedia) Text("Media: non-rotating (commonly SSD)")
                                        else ata.rotationRateRpm?.let { Text("Rotation rate: $it RPM") }
                                    }
                                    Text(ata.message, style = MaterialTheme.typography.bodySmall)
                                }
                                val smart = smartResults[device.deviceName]
                                if (smart != null) {
                                    Text(
                                        if (smart.success) "SMART: data retrieved" else "SMART: unavailable",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    if (smart.success) {
                                        Text(
                                            when (smart.thresholdExceeded) {
                                                true -> "SMART RETURN STATUS: threshold exceeded"
                                                false -> "SMART RETURN STATUS: no threshold exceeded"
                                                null -> "SMART RETURN STATUS: unknown signature"
                                            }
                                        )
                                        Text("Attributes: ${smart.attributes.size} · Threshold entries: ${smart.thresholds.size}")
                                        val smartProfile = SmartAttributeProfiles.resolve(
                                        inquiryResults[device.deviceName]?.vendor.orEmpty(),
                                        ataResults[device.deviceName]?.model.orEmpty()
                                    )
                                    smart.attributes.forEach { attribute ->
                                            val threshold = smart.thresholds[attribute.id]
                                            val label = SmartAttributeProfiles.describe(smartProfile, attribute.id).label
                                            Text(
                                                "$label (ID ${attribute.id}) · Current ${attribute.current} · Worst ${attribute.worst} · Threshold ${threshold ?: "N/A"} · Raw ${attribute.rawValueHex}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    Text(smart.message, style = MaterialTheme.typography.bodySmall)
                                    val historyAta = ataResults[device.deviceName]
                                    val historyKey = historyAta?.serialNumber?.takeIf { it.isNotBlank() }
                                        ?: (device.vendorId.toString() + ":" + device.productId + ":" + historyAta?.model.orEmpty().ifBlank { device.product })
                                    val matchingSamples = historySamples.filter { it.deviceKey == historyKey }
                                        .sortedBy { it.capturedAtMillis }
                                    val latestSample = matchingSamples.lastOrNull()
                                    val previousSample = matchingSamples.dropLast(1).lastOrNull()
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("SMART health signals", style = MaterialTheme.typography.titleMedium)
                                            when {
                                                !smart.success -> {
                                                    Text("Health status unavailable", style = MaterialTheme.typography.titleSmall)
                                                    Text("No valid SMART snapshot was obtained. Device identity or USB detection alone cannot establish drive health.", style = MaterialTheme.typography.bodySmall)
                                                }
                                                latestSample == null -> {
                                                    Text("SMART snapshot not saved", style = MaterialTheme.typography.titleSmall)
                                                    Text("SMART data was returned, but HDHA could not match it to a saved drive record. No health verdict is shown.", style = MaterialTheme.typography.bodySmall)
                                                }
                                                else -> {
                                                    val health = SmartHealthEstimator.assess(latestSample)
                                                    Text(health.label, style = MaterialTheme.typography.titleSmall)
                                                    Text("SMART status: " + when (latestSample.thresholdExceeded) {
                                                        true -> "threshold exceeded"
                                                        false -> "no threshold exceeded"
                                                        null -> "unknown"
                                                    }, style = MaterialTheme.typography.bodySmall)
                                                    Text("Relevant normalized attributes with thresholds: ${health.evidenceCount}", style = MaterialTheme.typography.bodySmall)
                                                    Text(health.explanation, style = MaterialTheme.typography.bodySmall)
                                                    Text("No overall health percentage is calculated. SMART cannot guarantee that a drive will not fail.", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }
                                    if (latestSample != null) {
                                        SmartTrendAnalyzer.compare(previousSample, latestSample).forEach { alert ->
                                            Text("[${alert.severity}] ${alert.message}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Text("SMART history is saved locally on this device.", style = MaterialTheme.typography.bodySmall)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onRequestPermission(device.deviceName) },
                                        enabled = !hasPermission(device.deviceName)
                                    ) {
                                        Text(if (hasPermission(device.deviceName)) "Permission granted" else "Request USB access")
                                    }
                                    Button(
                                        onClick = { onRunInquiry(device.deviceName) },
                                        enabled = hasPermission(device.deviceName) && device.interfaces.any {
                                            UsbMassStorageProfile.inspect(it)?.canAttemptBulkOnlyTransport == true
                                        }
                                    ) { Text("Read device identity") }
                                    Button(
                                        onClick = { onRunAtaIdentify(device.deviceName) },
                                        enabled = hasPermission(device.deviceName) && device.interfaces.any {
                                            UsbMassStorageProfile.inspect(it)?.canAttemptBulkOnlyTransport == true
                                        }
                                    ) { Text("Probe SAT / Identify ATA") }
                                    Button(
                                        onClick = { onRunSmart(device.deviceName) },
                                        enabled = hasPermission(device.deviceName) && device.interfaces.any {
                                            UsbMassStorageProfile.inspect(it)?.canAttemptBulkOnlyTransport == true
                                        }
                                    ) { Text("Read SMART data and status") }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Read-only only: SCSI INQUIRY, ATA IDENTIFY, SMART READ DATA, SMART READ THRESHOLDS, SMART RETURN STATUS and REQUEST SENSE. No SMART enable/disable, writes, formatting, repair, or sector tests.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
