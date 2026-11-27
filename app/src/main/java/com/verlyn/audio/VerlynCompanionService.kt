@file:Suppress("OVERRIDE_DEPRECATION")

package com.verlyn.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class VerlynCompanionService : CompanionDeviceService() {
    @Suppress("DEPRECATION")
    override fun onDeviceAppeared(address: String) = startCompanion(address)

    @Suppress("DEPRECATION")
    override fun onDeviceDisappeared(address: String) = stopCompanion()

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        startCompanion(associationInfo.deviceMacAddress?.toString().orEmpty())
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        stopCompanion()
    }

    private fun startCompanion(address: String) {
        if (address.isNotBlank()) {
            getSharedPreferences("verlyn_audio_preferences", MODE_PRIVATE).edit()
                .putString("companion_mac", address).putBoolean("companion_device_associated", true).apply()
        }
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, VerlynConnectionService::class.java)
                    .setAction(VerlynConnectionService.ACTION_COMPANION_APPEARED)
            )
        }.onFailure { Log.w("VerlynCompanion", "Unable to start the connected-headset service", it) }
    }

    private fun stopCompanion() {
        stopService(Intent(this, VerlynConnectionService::class.java))
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(VerlynConnectionService.NOTIFICATION_ID)
        }
    }
}

/** Static ACL/A2DP fallback so a previously associated headset can wake the process. */
class BluetoothConnectionReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val device = runCatching {
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }.getOrNull() ?: return
        val preferences = context.getSharedPreferences("verlyn_audio_preferences", Context.MODE_PRIVATE)
        val associated = preferences.getString("companion_mac", "").orEmpty()
        val name = runCatching { device.name.orEmpty() }.getOrDefault("")
        if (!(associated.isNotBlank() && associated.equals(device.address, true)) &&
            !name.contains("JBL Tune 770NC", true) && !name.contains("JBL Tune770NC", true)
        ) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> start(context)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> stop(context)
            android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                when (intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1)) {
                    android.bluetooth.BluetoothProfile.STATE_CONNECTED -> start(context)
                    android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> stop(context)
                }
            }
        }
    }

    private fun start(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VerlynConnectionService::class.java)
                    .setAction(VerlynConnectionService.ACTION_COMPANION_APPEARED)
            )
        }.onFailure { Log.w("VerlynCompanion", "Fallback wake-up failed", it) }
    }

    private fun stop(context: Context) {
        context.stopService(Intent(context, VerlynConnectionService::class.java))
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(VerlynConnectionService.NOTIFICATION_ID)
        }
    }
}
