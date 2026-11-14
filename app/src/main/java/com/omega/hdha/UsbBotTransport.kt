package com.omega.hdha

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-only BOT commands: SCSI INQUIRY and ATA IDENTIFY DEVICE.
 * Neither command modifies media or reads user data sectors.
 */
object UsbBotTransport {
    private const val TRANSFER_TIMEOUT_MS = 3_000
    private val nextTag = AtomicInteger(1)

    data class InquiryResult(
        val success: Boolean,
        val vendor: String = "",
        val product: String = "",
        val revision: String = "",
        val peripheralDeviceType: Int? = null,
        val removable: Boolean = false,
        val message: String
    )

    data class AtaIdentifyResult(
        val success: Boolean,
        val satSupported: Boolean?,
        val serialNumber: String = "",
        val firmware: String = "",
        val model: String = "",
        val smartSupported: Boolean = false,
        val smartEnabled: Boolean = false,
        val lba48Supported: Boolean = false,
        val nonRotatingMedia: Boolean = false,
        val rotationRateRpm: Int? = null,
        val capacityBytes: Long = 0L,
        val message: String
    )

    fun inquiry(manager: UsbManager, device: UsbDevice): InquiryResult {
        if (!manager.hasPermission(device)) return inquiryFailure("USB permission has not been granted.")
        val candidate = findBotInterface(device)
            ?: return inquiryFailure("No compatible SCSI/BOT interface with Bulk IN and Bulk OUT endpoints was found.")
        val bulkIn = findEndpoint(candidate, UsbConstants.USB_DIR_IN)
            ?: return inquiryFailure("Bulk IN endpoint is missing.")
        val bulkOut = findEndpoint(candidate, UsbConstants.USB_DIR_OUT)
            ?: return inquiryFailure("Bulk OUT endpoint is missing.")
        val connection = manager.openDevice(device)
            ?: return inquiryFailure("Android could not open the USB device.")
        var claimed = false
        return try {
            claimed = connection.claimInterface(candidate, false)
            check(claimed) { "Could not claim the USB mass-storage interface." }
            val data = executeReadOnlyDataIn(
                connection, bulkIn, bulkOut, BotProtocol.buildInquiryCdb(),
                BotProtocol.INQUIRY_DATA_LENGTH
            )
            val parsed = BotProtocol.parseInquiry(data)
            InquiryResult(
                success = true,
                vendor = parsed.vendor,
                product = parsed.product,
                revision = parsed.revision,
                peripheralDeviceType = parsed.peripheralDeviceType,
                removable = parsed.removable,
                message = "SCSI INQUIRY succeeded. Device identity only; SMART health is not assessed."
            )
        } catch (e: Exception) {
            inquiryFailure(e.message ?: "SCSI INQUIRY failed.")
        } finally {
            if (claimed) try { connection.releaseInterface(candidate) } catch (_: Exception) { }
            connection.close()
        }
    }

    /**
     * Probes SAT using ATA PASS-THROUGH(16) and IDENTIFY DEVICE (0xEC).
     * A valid 512-byte response confirms pass-through. A failure is indeterminate,
     * because some USB bridges reject or hide ATA pass-through errors.
     */
    fun identifyAta(manager: UsbManager, device: UsbDevice): AtaIdentifyResult {
        if (!manager.hasPermission(device)) return ataFailure(null, "USB permission has not been granted.")
        val candidate = findBotInterface(device)
            ?: return ataFailure(null, "No compatible SCSI/BOT interface; SAT cannot be probed.")
        val bulkIn = findEndpoint(candidate, UsbConstants.USB_DIR_IN)
            ?: return ataFailure(null, "Bulk IN endpoint is missing.")
        val bulkOut = findEndpoint(candidate, UsbConstants.USB_DIR_OUT)
            ?: return ataFailure(null, "Bulk OUT endpoint is missing.")
        val connection = manager.openDevice(device)
            ?: return ataFailure(null, "Android could not open the USB device.")
        var claimed = false
        return try {
            claimed = connection.claimInterface(candidate, false)
            check(claimed) { "Could not claim the USB mass-storage interface." }
            val payload = executeReadOnlyDataIn(
                connection, bulkIn, bulkOut,
                BotProtocol.buildAtaIdentifyDeviceCdb(),
                BotProtocol.ATA_IDENTIFY_DATA_LENGTH
            )
            val parsed = BotProtocol.parseAtaIdentify(payload)
            AtaIdentifyResult(
                success = true,
                satSupported = true,
                serialNumber = parsed.serialNumber,
                firmware = parsed.firmware,
                model = parsed.model,
                smartSupported = parsed.smartSupported,
                smartEnabled = parsed.smartEnabled,
                lba48Supported = parsed.lba48Supported,
                nonRotatingMedia = parsed.nonRotatingMedia,
                rotationRateRpm = parsed.rotationRateRpm,
                capacityBytes = parsed.capacityBytes,
                message = "SAT ATA IDENTIFY succeeded. This is device information, not a SMART health assessment."
            )
        } catch (e: Exception) {
            ataFailure(
                null,
                "SAT/ATA IDENTIFY was not confirmed: ${e.message ?: e.javaClass.simpleName}. This does not prove SAT is unsupported."
            )
        } finally {
            if (claimed) try { connection.releaseInterface(candidate) } catch (_: Exception) { }
            connection.close()
        }
    }

    data class SmartResult(
        val success: Boolean,
        val attributes: List<SmartProtocol.Attribute> = emptyList(),
        val thresholds: Map<Int, Int> = emptyMap(),
        val thresholdExceeded: Boolean? = null,
        val ataStatus: Int? = null,
        val ataError: Int? = null,
        val message: String
    )

    /**
     * Reads SMART READ DATA, READ THRESHOLDS and RETURN STATUS only.
     * SMART must already be supported and enabled; this method never enables it.
     */
    fun readSmart(manager: UsbManager, device: UsbDevice): SmartResult {
        if (!manager.hasPermission(device)) return smartFailure("USB permission has not been granted.")
        val identify = identifyAta(manager, device)
        if (!identify.success) return smartFailure("SAT is not confirmed: ${identify.message}")
        if (!identify.smartSupported) return smartFailure("The drive reports that SMART is not supported.")
        if (!identify.smartEnabled) return smartFailure("SMART is supported but not enabled. HDHA will not enable it.")

        val candidate = findBotInterface(device)
            ?: return smartFailure("No compatible SCSI/BOT interface was found.")
        val bulkIn = findEndpoint(candidate, UsbConstants.USB_DIR_IN)
            ?: return smartFailure("Bulk IN endpoint is missing.")
        val bulkOut = findEndpoint(candidate, UsbConstants.USB_DIR_OUT)
            ?: return smartFailure("Bulk OUT endpoint is missing.")
        val connection = manager.openDevice(device)
            ?: return smartFailure("Android could not open the USB device.")
        var claimed = false
        return try {
            claimed = connection.claimInterface(candidate, false)
            check(claimed) { "Could not claim the USB mass-storage interface." }

            val rawData = executeReadOnlyDataIn(
                connection, bulkIn, bulkOut, SmartProtocol.buildReadDataCdb(),
                SmartProtocol.SECTOR_LENGTH
            )
            val smartData = SmartProtocol.parseReadData(rawData)

            val rawThresholds = executeReadOnlyDataIn(
                connection, bulkIn, bulkOut, SmartProtocol.buildReadThresholdsCdb(),
                SmartProtocol.SECTOR_LENGTH
            )
            val thresholds = SmartProtocol.parseReadThresholds(rawThresholds)

            val returnStatusCsw = executeNoDataCommand(
                connection, bulkIn, bulkOut, SmartProtocol.buildReturnStatusCdb()
            )
            check(returnStatusCsw.status == 1) {
                if (returnStatusCsw.status == 0) {
                    "SMART RETURN STATUS did not return the requested ATA sense descriptor."
                } else {
                    "SMART RETURN STATUS ended with a BOT phase error."
                }
            }

            val sense = executeReadOnlyDataIn(
                connection, bulkIn, bulkOut, SmartProtocol.buildRequestSenseCdb(),
                SmartProtocol.REQUEST_SENSE_ALLOCATION_LENGTH, allowShort = true
            )
            val ataStatus = SmartProtocol.parseAtaReturnDescriptor(sense)
            SmartResult(
                success = true,
                attributes = smartData.attributes,
                thresholds = thresholds,
                thresholdExceeded = ataStatus.thresholdExceeded,
                ataStatus = ataStatus.status,
                ataError = ataStatus.error,
                message = if (ataStatus.thresholdExceeded == null) {
                    "SMART data read successfully, but the ATA RETURN STATUS signature is unknown. No health verdict is inferred."
                } else if (ataStatus.thresholdExceeded) {
                    "SMART RETURN STATUS reports that a threshold has been exceeded. Back up important data promptly."
                } else {
                    "SMART RETURN STATUS reports that no threshold has been exceeded. This does not guarantee the drive will not fail."
                }
            )
        } catch (e: Exception) {
            smartFailure("SMART read unavailable: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (claimed) try { connection.releaseInterface(candidate) } catch (_: Exception) { }
            connection.close()
        }
    }

    private fun executeReadOnlyDataIn(
        connection: UsbDeviceConnection,
        bulkIn: UsbEndpoint,
        bulkOut: UsbEndpoint,
        cdb: ByteArray,
        expectedLength: Int,
        allowShort: Boolean = false
    ): ByteArray {
        require(expectedLength > 0) { "Read-only BOT data length must be positive." }
        val tag = nextTag.getAndIncrement().toLong().and(0xFFFF_FFFFL).let { if (it == 0L) 1L else it }
        val cbw = BotProtocol.buildCbw(tag, expectedLength.toLong(), cdb)
        val cbwSent = connection.bulkTransfer(bulkOut, cbw, cbw.size, TRANSFER_TIMEOUT_MS)
        check(cbwSent == cbw.size) { "BOT command wrapper transfer failed or timed out." }

        val data = ByteArray(expectedLength)
        val dataRead = connection.bulkTransfer(bulkIn, data, data.size, TRANSFER_TIMEOUT_MS)
        check(dataRead > 0 && dataRead <= expectedLength) { "BOT data stage failed or returned no bytes ($dataRead)." }
        if (!allowShort) check(dataRead == expectedLength) {
            "BOT data stage incomplete ($dataRead/$expectedLength bytes)."
        }

        val cswBytes = ByteArray(BotProtocol.CSW_LENGTH)
        val cswRead = connection.bulkTransfer(bulkIn, cswBytes, cswBytes.size, TRANSFER_TIMEOUT_MS)
        check(cswRead == cswBytes.size) { "BOT status wrapper transfer failed or timed out." }

        val csw = BotProtocol.parseCsw(
            cswBytes, expectedTag = tag, expectedTransferLength = expectedLength.toLong()
        )
        check(dataRead.toLong() + csw.residue == expectedLength.toLong()) {
            "BOT data residue is inconsistent with the received data."
        }
        check(csw.status == 0) {
            if (csw.status == 1) "SCSI command failed (CHECK CONDITION); SAT was not confirmed."
            else "BOT phase error; reconnect the drive before retrying."
        }
        return data.copyOf(dataRead)
    }

    private fun executeNoDataCommand(
        connection: UsbDeviceConnection,
        bulkIn: UsbEndpoint,
        bulkOut: UsbEndpoint,
        cdb: ByteArray
    ): BotProtocol.CommandStatusWrapper {
        val tag = nextTag.getAndIncrement().toLong().and(0xFFFF_FFFFL).let { if (it == 0L) 1L else it }
        val cbw = BotProtocol.buildCbw(tag, 0L, cdb, dataIn = false)
        val cbwSent = connection.bulkTransfer(bulkOut, cbw, cbw.size, TRANSFER_TIMEOUT_MS)
        check(cbwSent == cbw.size) { "BOT no-data command wrapper transfer failed or timed out." }
        val cswBytes = ByteArray(BotProtocol.CSW_LENGTH)
        val cswRead = connection.bulkTransfer(bulkIn, cswBytes, cswBytes.size, TRANSFER_TIMEOUT_MS)
        check(cswRead == cswBytes.size) { "BOT no-data status wrapper transfer failed or timed out." }
        return BotProtocol.parseCsw(cswBytes, expectedTag = tag, expectedTransferLength = 0L)
    }

    private fun findBotInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).asSequence()
            .map(device::getInterface)
            .firstOrNull { intf ->
                intf.interfaceClass == UsbMassStorageProfile.CLASS_MASS_STORAGE &&
                    intf.interfaceSubclass == UsbMassStorageProfile.SUBCLASS_SCSI_TRANSPARENT &&
                    intf.interfaceProtocol == UsbMassStorageProfile.PROTOCOL_BULK_ONLY &&
                    findEndpoint(intf, UsbConstants.USB_DIR_IN) != null &&
                    findEndpoint(intf, UsbConstants.USB_DIR_OUT) != null
            }

    private fun findEndpoint(intf: UsbInterface, direction: Int): UsbEndpoint? =
        (0 until intf.endpointCount).asSequence()
            .map(intf::getEndpoint)
            .firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == direction
            }

    private fun inquiryFailure(message: String) = InquiryResult(success = false, message = message)
    private fun smartFailure(message: String) = SmartResult(success = false, message = message)
    private fun ataFailure(satSupported: Boolean?, message: String) =
        AtaIdentifyResult(success = false, satSupported = satSupported, message = message)
}
