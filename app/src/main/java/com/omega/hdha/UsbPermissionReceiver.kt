package com.omega.hdha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.widget.Toast

class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_USB_PERMISSION) return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        Toast.makeText(context, if (granted) "USB permission granted" else "USB permission denied", Toast.LENGTH_SHORT).show()
    }
    companion object {
        const val ACTION_USB_PERMISSION = "com.omega.hdha.USB_PERMISSION"
    }
}
