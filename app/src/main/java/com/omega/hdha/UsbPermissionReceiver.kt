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
        Toast.makeText(
            context,
            if (granted) "USB permission granted — refreshing device controls" else "USB permission denied",
            Toast.LENGTH_SHORT
        ).show()

        // The permission result is delivered to this explicit receiver. Notify the
        // foreground activity so its Compose state is refreshed immediately.
        context.sendBroadcast(
            Intent(ACTION_PERMISSION_RESULT).setPackage(context.packageName)
        )
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.omega.hdha.USB_PERMISSION"
        const val ACTION_PERMISSION_RESULT = "com.omega.hdha.USB_PERMISSION_RESULT"
    }
}
