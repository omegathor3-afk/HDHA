package com.omega.hdha

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

data class UsbEndpointInfo(
    val address: Int,
    val direction: String,
    val type: String,
    val maxPacketSize: Int
)

data class UsbInterfaceInfo(
    val id: Int,
    val interfaceClass: Int,
    val subclass: Int,
    val protocol: Int,
    val endpoints: List<UsbEndpointInfo>
) {
    val isMassStorage: Boolean
        get() = interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
}

data class UsbDriveCandidate(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String,
    val product: String,
    val interfaces: List<UsbInterfaceInfo>
) {
    val hasMassStorageInterface: Boolean
        get() = interfaces.any(UsbInterfaceInfo::isMassStorage)
}

object UsbDeviceInspector {
    fun discover(manager: UsbManager): List<UsbDriveCandidate> =
        manager.deviceList.values.map(::describe)
            .sortedWith(compareBy<UsbDriveCandidate> { !it.hasMassStorageInterface }.thenBy { it.deviceName })

    fun describe(device: UsbDevice): UsbDriveCandidate {
        val interfaces = (0 until device.interfaceCount).map { index ->
            val usbInterface = device.getInterface(index)
            val endpoints = (0 until usbInterface.endpointCount).map { endpointIndex ->
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                UsbEndpointInfo(
                    address = endpoint.address,
                    direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
                    type = when (endpoint.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                        else -> "CONTROL"
                    },
                    maxPacketSize = endpoint.maxPacketSize
                )
            }
            UsbInterfaceInfo(
                id = usbInterface.id,
                interfaceClass = usbInterface.interfaceClass,
                subclass = usbInterface.interfaceSubclass,
                protocol = usbInterface.interfaceProtocol,
                endpoints = endpoints
            )
        }
        return UsbDriveCandidate(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturer = device.manufacturerName ?: "Unknown manufacturer",
            product = device.productName ?: "Unknown USB device",
            interfaces = interfaces
        )
    }
}
