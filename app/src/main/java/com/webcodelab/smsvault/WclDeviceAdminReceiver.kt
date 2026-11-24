package com.webcodelab.smsvault

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

class WclDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        SecurityKeyStore.setProtectionFlagEnabled(context, true)

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = getComponentName(context)
        if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                dpm.setUninstallBlocked(admin, context.packageName, true)
            } catch (_: Exception) {
            }
        }

        Toast.makeText(
            context,
            "WCL Uninstall Protection enabled.",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return if (SecurityKeyStore.isUninstallBlockedByKeyRule(context)) {
            "WCL has an active Valid API Key. Remove the API Key or replace it with an invalid key inside WCL -> SMS Manager before uninstalling."
        } else {
            "API Key is removed or invalid. Disabling Device Admin will allow uninstalling WCL."
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        SecurityKeyStore.setProtectionFlagEnabled(context, false)
        Toast.makeText(
            context,
            "WCL Device Admin protection disabled.",
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, WclDeviceAdminReceiver::class.java)
        }

        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return dpm.isAdminActive(getComponentName(context))
        }

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return dpm.isDeviceOwnerApp(context.packageName)
        }

        fun isUninstallBlockedByOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            if (!dpm.isDeviceOwnerApp(context.packageName)) return false
            return try {
                dpm.isUninstallBlocked(getComponentName(context), context.packageName)
            } catch (_: Exception) {
                false
            }
        }

        fun enableOwnerBlockIfApplicable(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            if (!dpm.isDeviceOwnerApp(context.packageName)) return false
            return try {
                dpm.setUninstallBlocked(getComponentName(context), context.packageName, true)
                SecurityKeyStore.setProtectionFlagEnabled(context, true)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun syncProtectionWithKeyRule(context: Context) {
            if (SecurityKeyStore.isUninstallBlockedByKeyRule(context)) {
                SecurityKeyStore.setProtectionFlagEnabled(context, true)
                enableOwnerBlockIfApplicable(context)
            } else {
                disableProtectionAuthorized(context)
            }
        }

        @Suppress("DEPRECATION")
        fun disableProtectionAuthorized(context: Context): Boolean {
            // Strict server/key-state guard: never allow disabling while Valid API key is still active
            if (SecurityKeyStore.isUninstallBlockedByKeyRule(context)) {
                enableOwnerBlockIfApplicable(context)
                return false
            }

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            val admin = getComponentName(context)
            var changed = false

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                try {
                    dpm.setUninstallBlocked(admin, context.packageName, false)
                    dpm.clearDeviceOwnerApp(context.packageName)
                    changed = true
                } catch (_: Exception) {
                }
            }

            if (dpm.isAdminActive(admin)) {
                try {
                    dpm.removeActiveAdmin(admin)
                    changed = true
                } catch (_: Exception) {
                }
            }

            SecurityKeyStore.setProtectionFlagEnabled(context, false)
            return changed || !isAdminActive(context)
        }
    }
}
