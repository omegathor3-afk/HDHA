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
    ): InquiryResult {
        val tag = (nextTag.getAndIncrement().toLong() and 0xFFFF_FFFFL).let {
            if (it == 0L) 1L else it
        }
        val expectedLength = BotProtocol.INQUIRY_DATA_LENGTH
        val cbw = BotProtocol.buildCbw(tag, expectedLength.toLong(), BotProtocol.buildInquiryCdb())

        val cbwSent = connection.bulkTransfer(bulkOut, cbw, cbw.size, TRANSFER_TIMEOUT_MS)
        if (cbwSent != cbw.size) return failure("BOT command wrapper transfer failed or timed out.")

        val data = ByteArray(expectedLength)
        val dataRead = connection.bulkTransfer(bulkIn, data, data.size, TRANSFER_TIMEOUT_MS)
        if (dataRead < 0) return failure("SCSI INQUIRY data stage failed or timed out.")
        if (dataRead < expectedLength) return failure("SCSI INQUIRY returned only $dataRead of $expectedLength bytes.")

        val cswBytes = ByteArray(BotProtocol.CSW_LENGTH)
        val cswRead = connection.bulkTransfer(bulkIn, cswBytes, cswBytes.size, TRANSFER_TIMEOUT_MS)
        if (cswRead != cswBytes.size) return failure("BOT status wrapper transfer failed or timed out.")

        val csw = try {
            BotProtocol.parseCsw(cswBytes, expectedTag = tag, expectedTransferLength = expectedLength.toLong())
        } catch (e: IllegalArgumentException) {
            return failure(e.message ?: "Invalid BOT status wrapper.")
        }
        if (dataRead.toLong() + csw.residue != expectedLength.toLong()) return failure("BOT data residue is inconsistent with the received data.")
        if (csw.status != 0) {
            return failure(
                when (csw.status) {
                    1 -> "The USB bridge reported that SCSI INQUIRY failed."
                    else -> "The USB bridge reported a BOT phase error; reconnect the drive."
                }
            )
        }

        val inquiry = try {
            BotProtocol.parseInquiry(data)
        } catch (e: IllegalArgumentException) {
            return failure(e.message ?: "Invalid SCSI INQUIRY response.")
        }
        return InquiryResult(
            success = true,
            vendor = inquiry.vendor,
            product = inquiry.product,
            revision = inquiry.revision,
            peripheralDeviceType = inquiry.peripheralDeviceType,
            removable = inquiry.removable,
            message = "SCSI INQUIRY succeeded. This identifies the device; it does not report SMART health."
        )
    }

    private fun failure(message: String) = InquiryResult(success = false, message = message)
}
