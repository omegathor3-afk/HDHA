package com.omega.hdha

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal, read-only USB Mass Storage Bulk-Only Transport implementation.
 * It sends only SCSI INQUIRY; it never reads or writes user sectors.
 */
object UsbBotTransport {
    private const val TRANSFER_TIMEOUT_MS = 3_000
    private val nextTag = AtomicInteger(1)

    data class AtaIdentifyResult(
        val success: Boolean,
        val serialNumber: String = "",
        val firmware: String = "",
        val model: String = "",
        val smartSupported: Boolean = false,
        val smartEnabled: Boolean = false,
        val lba48Supported: Boolean = false,
        val rotationRateRpm: Int? = null,
        val message: String
    )

    data class AtaIdentifyResult(
        val success: Boolean,
        val satSupported: Boolean?,
        val serialNumber: String = "",
        val firmwareRevision: String = "",
        val model: String = "",
        val supportsLba48: Boolean = false,
        val capacityBytes: Long = 0L,
        val message: String
    )

    data class InquiryResult(
        val success: Boolean,
        val vendor: String = "",
        val product: String = "",
        val revision: String = "",
        val peripheralDeviceType: Int? = null,
        val removable: Boolean = false,
        val message: String
    )

    fun inquiry(manager: UsbManager, device: UsbDevice): InquiryResult {
        if (!manager.hasPermission(device)) {
            return failure("USB permission has not been granted.")
        }

        val candidate = (0 until device.interfaceCount)
            .asSequence()
            .map(device::getInterface)
            .firstOrNull { intf ->
                intf.interfaceClass == UsbMassStorageProfile.CLASS_MASS_STORAGE &&
                    intf.interfaceSubclass == UsbMassStorageProfile.SUBCLASS_SCSI_TRANSPARENT &&
                    intf.interfaceProtocol == UsbMassStorageProfile.PROTOCOL_BULK_ONLY &&
                    (0 until intf.endpointCount).any {
                        intf.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            intf.getEndpoint(it).direction == UsbConstants.USB_DIR_IN
                    } &&
                    (0 until intf.endpointCount).any {
                        intf.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            intf.getEndpoint(it).direction == UsbConstants.USB_DIR_OUT
                    }
            } ?: return failure("No compatible SCSI/BOT interface with Bulk IN and Bulk OUT endpoints was found.")

        val bulkIn = findEndpoint(candidate, UsbConstants.USB_DIR_IN)
            ?: return failure("Bulk IN endpoint is missing.")
        val bulkOut = findEndpoint(candidate, UsbConstants.USB_DIR_OUT)
            ?: return failure("Bulk OUT endpoint is missing.")

        val connection = manager.openDevice(device)
            ?: return failure("Android could not open the USB device.")

        var claimed = false
        return try {
            claimed = connection.claimInterface(candidate, false)
            if (!claimed) {
                failure("Could not claim the USB mass-storage interface.")
            } else {
                executeInquiry(connection, bulkIn, bulkOut)
            }
        } catch (e: Exception) {
            failure("USB inquiry failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (claimed) {
                try { connection.releaseInterface(candidate) } catch (_: Exception) { }
            }
            connection.close()
        }
    }


    /** Read-only SAT probe: ATA IDENTIFY DEVICE returns one 512-byte identification block. */
    fun identifyAta(manager: UsbManager, device: UsbDevice): AtaIdentifyResult {
        if (!manager.hasPermission(device)) return ataFailure("USB permission has not been granted.")
        val candidate = (0 until device.interfaceCount).asSequence().map(device::getInterface).firstOrNull { intf ->
            intf.interfaceClass == UsbMassStorageProfile.CLASS_MASS_STORAGE &&
                intf.interfaceSubclass == UsbMassStorageProfile.SUBCLASS_SCSI_TRANSPARENT &&
                intf.interfaceProtocol == UsbMassStorageProfile.PROTOCOL_BULK_ONLY &&
                findEndpoint(intf, UsbConstants.USB_DIR_IN) != null &&
                findEndpoint(intf, UsbConstants.USB_DIR_OUT) != null
        } ?: return ataFailure("A compatible SCSI/BOT interface was not found.")
        val bulkIn = findEndpoint(candidate, UsbConstants.USB_DIR_IN) ?: return ataFailure("Bulk IN endpoint is missing.")
        val bulkOut = findEndpoint(candidate, UsbConstants.USB_DIR_OUT) ?: return ataFailure("Bulk OUT endpoint is missing.")
        val connection = manager.openDevice(device) ?: return ataFailure("Android could not open the USB device.")
        var claimed = false
        return try {
            claimed = connection.claimInterface(candidate, false)
            check(claimed) { "Could not claim the USB mass-storage interface." }
            val data = executeReadOnlyDataIn(connection, bulkIn, bulkOut, BotProtocol.buildAtaIdentifyDeviceCdb(), 512)
            val identify = BotProtocol.parseAtaIdentify(data)
            AtaIdentifyResult(
                success = true,
                serialNumber = identify.serialNumber,
                firmware = identify.firmware,
                model = identify.model,
                smartSupported = identify.smartSupported,
                smartEnabled = identify.smartEnabled,
                lba48Supported = identify.lba48Supported,
                rotationRateRpm = identify.rotationRateRpm,
                message = "ATA IDENTIFY succeeded through SAT. This confirms command pass-through, not drive health."
            )
        } catch (e: Exception) {
            ataFailure("SAT/ATA IDENTIFY unavailable: ${e.message ?: e.javaClass.simpleName}. This does not prove the bridge lacks SAT.")
        } finally {
            if (claimed) try { connection.releaseInterface(candidate) } catch (_: Exception) { }
            connection.close()
        }
    }

    private fun executeReadOnlyDataIn(connection: UsbDeviceConnection, bulkIn: UsbEndpoint, bulkOut: UsbEndpoint, cdb: ByteArray, expectedLength: Int): ByteArray {
        val tag = (nextTag.getAndIncrement().toLong() and 0xFFFF_FFFFL).let { if (it == 0L) 1L else it }
        val cbw = BotProtocol.buildCbw(tag, expectedLength.toLong(), cdb)
        check(connection.bulkTransfer(bulkOut, cbw, cbw.size, TRANSFER_TIMEOUT_MS) == cbw.size) { "BOT command wrapper transfer failed or timed out." }
        val data = ByteArray(expectedLength)
        val received = connection.bulkTransfer(bulkIn, data, data.size, TRANSFER_TIMEOUT_MS)
        check(received == expectedLength) { "ATA data stage was incomplete ($received/$expectedLength bytes)." }
        val cswBytes = ByteArray(BotProtocol.CSW_LENGTH)
        check(connection.bulkTransfer(bulkIn, cswBytes, cswBytes.size, TRANSFER_TIMEOUT_MS) == cswBytes.size) { "BOT status wrapper transfer failed or timed out." }
        val csw = BotProtocol.parseCsw(cswBytes, expectedTag = tag, expectedTransferLength = expectedLength.toLong())
        check(received.toLong() + csw.residue == expectedLength.toLong()) { "BOT data residue is inconsistent." }
        check(csw.status == 0) { "SCSI/ATA PASS-THROUGH command failed (BOT status ${csw.status})." }
        return data
    }

    private fun ataFailure(message: String) = AtaIdentifyResult(success = false, message = message)

    /**
     * Probes SAT by issuing only ATA IDENTIFY DEVICE through ATA PASS-THROUGH(12).
     * A rejected command is reported as SAT not confirmed; it is never interpreted
     * as a drive-health result.
     */
    fun identifyAta(manager: UsbManager, device: UsbDevice): AtaIdentifyResult {
        if (!manager.hasPermission(device)) {
            return ataFailure(null, "USB permission has not been granted.")
        }
        val candidate = (0 until device.interfaceCount)
            .asSequence()
            .map(device::getInterface)
            .firstOrNull { intf ->
                intf.interfaceClass == UsbMassStorageProfile.CLASS_MASS_STORAGE &&
                    intf.interfaceSubclass == UsbMassStorageProfile.SUBCLASS_SCSI_TRANSPARENT &&
                    intf.interfaceProtocol == UsbMassStorageProfile.PROTOCOL_BULK_ONLY &&
                    findEndpoint(intf, UsbConstants.USB_DIR_IN) != null &&
                    findEndpoint(intf, UsbConstants.USB_DIR_OUT) != null
            } ?: return ataFailure(null, "No compatible BOT interface. SAT cannot be probed.")
        val bulkIn = findEndpoint(candidate, UsbConstants.USB_DIR_IN)
            ?: return ataFailure(null, "Bulk IN endpoint is missing.")
        val bulkOut = findEndpoint(candidate, UsbConstants.USB_DIR_OUT)
            ?: return ataFailure(null, "Bulk OUT endpoint is missing.")
        val connection = manager.openDevice(device)
            ?: return ataFailure(null, "Android could not open the USB device.")
        var claimed = false
        return try {
            claimed = connection.claimInterface(candidate, false)
            if (!claimed) {
                ataFailure(null, "Could not claim the USB mass-storage interface.")
            } else {
                executeAtaIdentify(connection, bulkIn, bulkOut)
            }
        } catch (e: Exception) {
            ataFailure(null, "SAT probe failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (claimed) {
                try { connection.releaseInterface(candidate) } catch (_: Exception) { }
            }
            connection.close()
        }
    }

    private fun executeAtaIdentify(
        connection: UsbDeviceConnection,
        bulkIn: UsbEndpoint,
        bulkOut: UsbEndpoint
    ): AtaIdentifyResult {
        val tag = (nextTag.getAndIncrement().toLong() and 0xFFFF_FFFFL).let {
            if (it == 0L) 1L else it
        }
        val expectedLength = 512
        val cbw = BotProtocol.buildCbw(
            tag, expectedLength.toLong(), BotProtocol.buildAtaPassThrough12IdentifyCdb()
        )
        val cbwSent = connection.bulkTransfer(bulkOut, cbw, cbw.size, TRANSFER_TIMEOUT_MS)
        if (cbwSent != cbw.size) return ataFailure(null, "SAT probe CBW failed or timed out.")
        val data = ByteArray(expectedLength)
        val dataRead = connection.bulkTransfer(bulkIn, data, data.size, TRANSFER_TIMEOUT_MS)
        if (dataRead != expectedLength) {
            return ataFailure(null, "ATA IDENTIFY data stage incomplete ($dataRead/$expectedLength).")
        }
        val cswBytes = ByteArray(BotProtocol.CSW_LENGTH)
        val cswRead = connection.bulkTransfer(bulkIn, cswBytes, cswBytes.size, TRANSFER_TIMEOUT_MS)
        if (cswRead != cswBytes.size) return ataFailure(null, "SAT probe CSW failed or timed out.")
        val csw = try {
            BotProtocol.parseCsw(cswBytes, expectedTag = tag, expectedTransferLength = expectedLength.toLong())
        } catch (e: IllegalArgumentException) {
            return ataFailure(null, e.message ?: "Invalid BOT status wrapper during SAT probe.")
        }
        if (dataRead.toLong() + csw.residue != expectedLength.toLong()) {
            return ataFailure(null, "SAT probe returned inconsistent data residue.")
        }
        if (csw.status != 0) {
            return ataFailure(
                csw.status == 1,
                if (csw.status == 1) {
                    "SAT not confirmed: USB bridge rejected ATA PASS-THROUGH(12) / IDENTIFY DEVICE."
                } else {
                    "SAT probe encountered a BOT phase error; reconnect the drive."
                }
            )
        }
        val identify = try {
            BotProtocol.parseAtaIdentify(data)
        } catch (e: IllegalArgumentException) {
            return ataFailure(null, "ATA IDENTIFY response was invalid: ${e.message}")
        }
        return AtaIdentifyResult(
            success = true,
            satSupported = true,
            serialNumber = identify.serialNumber,
            firmwareRevision = identify.firmwareRevision,
            model = identify.model,
            supportsLba48 = identify.supportsLba48,
            capacityBytes = identify.capacityBytes,
            message = "SAT ATA IDENTIFY succeeded. This is device information, not a SMART health assessment."
        )
    }

    private fun ataFailure(satSupported: Boolean?, message: String) =
        AtaIdentifyResult(success = false, satSupported = satSupported, message = message)

    private fun findEndpoint(intf: UsbInterface, direction: Int): UsbEndpoint? =
        (0 until intf.endpointCount)
            .map(intf::getEndpoint)
            .firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == direction
            }

    private fun executeInquiry(
        connection: UsbDeviceConnection,
        bulkIn: UsbEndpoint,
        bulkOut: UsbEndpoint
    ): InquiryResult = try {
        val inquiry = BotProtocol.parseInquiry(
            executeReadOnlyDataIn(connection, bulkIn, bulkOut, BotProtocol.buildInquiryCdb(), BotProtocol.INQUIRY_DATA_LENGTH)
        )
        InquiryResult(
            success = true, vendor = inquiry.vendor, product = inquiry.product,
            revision = inquiry.revision, peripheralDeviceType = inquiry.peripheralDeviceType,
            removable = inquiry.removable,
            message = "SCSI INQUIRY succeeded. This identifies the device; it does not report SMART health."
        )
    } catch (e: Exception) {
        failure(e.message ?: "SCSI INQUIRY failed.")
    }

    private fun failure(message: String) = InquiryResult(success = false, message = message)
}
