package com.nmaclean.tvheadend.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.nmaclean.tvheadend.ui.MainActivity

/**
 * Handles [PackageInstaller] session commit callbacks and `MY_PACKAGE_REPLACED`
 * so self-updates complete inline without leaving the user on Android's full-screen "Done" screen.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UpdateInstallReceiver"
        const val ACTION_INSTALL_COMMIT = "com.nmaclean.tvheadend.ACTION_INSTALL_COMMIT"
        private const val PREFS = "tvh_updater_prefs"
        private const val KEY_PENDING_SELF_UPDATE = "pending_self_update"

        fun markPendingSelfUpdate(context: Context, pending: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING_SELF_UPDATE, pending)
                .apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INSTALL_COMMIT -> handleInstallCommit(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> handlePackageReplaced(context)
        }
    }

    private fun handleInstallCommit(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "PackageInstaller commit status=$status, message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Self-update succeeded")
            }
            else -> {
                markPendingSelfUpdate(context, false)
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(
                        context,
                        "Update install failed: ${message ?: "status $status"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun handlePackageReplaced(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wasSelfUpdate = prefs.getBoolean(KEY_PENDING_SELF_UPDATE, false)
        if (!wasSelfUpdate) return

        markPendingSelfUpdate(context, false)
        Log.i(TAG, "App replaced after self-update; relaunching MainActivity")
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(launchIntent)
    }
}
