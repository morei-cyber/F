package jp.focusroom.nativeapp

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/** Android Enterprise QR provisioning: choose fully-managed / Device Owner mode. */
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
        )
        setResult(RESULT_OK, result)
        finish()
    }
}

/** Called by Android setup after the package becomes Device Owner. */
class AdminPolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            DevicePolicyHelper.configure(this)
            setResult(RESULT_OK)
        } catch (_: Exception) {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
