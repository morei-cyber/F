package jp.focusroom.nativeapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

object DevicePolicyHelper {
    fun isOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            .isDeviceOwnerApp(context.packageName)

    fun configure(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, AdminReceiver::class.java)
        check(dpm.isDeviceOwnerApp(context.packageName)) { "Device Owner登録が必要です" }
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            // Allow no optional system features while the app is in Lock Task Mode.
            dpm.setLockTaskFeatures(admin, 0)
        }
    }
}
