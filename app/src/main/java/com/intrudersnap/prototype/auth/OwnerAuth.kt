package com.intrudersnap.prototype.auth

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * Owner authentication using the platform BiometricPrompt (API 30+):
 * strong biometric OR the device's own PIN / pattern / password.
 *
 * IntruderSnap never sees or stores the credential; Android verifies it.
 */
object OwnerAuth {

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    sealed class Availability {
        data object Available : Availability()
        data class Unavailable(val reason: String) : Availability()
    }

    fun availability(context: Context): Availability {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            return Availability.Unavailable("Set a screen lock (PIN, pattern or password) to protect IntruderSnap.")
        }
        return when (context.getSystemService(BiometricManager::class.java).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.Unavailable("No screen lock or biometric is enrolled.")
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.Unavailable("Authentication hardware is temporarily unavailable.")
            else -> Availability.Unavailable("Owner authentication is not available on this device.")
        }
    }

    /**
     * Shows the system authentication prompt. [onResult] receives true on success, or false
     * with a message (cancelled, too many attempts, ...). Runs callbacks on the main thread.
     */
    fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String,
        onResult: (success: Boolean, message: String?) -> Unit,
    ) {
        when (val a = availability(activity)) {
            is Availability.Unavailable -> {
                onResult(false, a.reason)
                return
            }
            Availability.Available -> Unit
        }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    OwnerSession.markAuthenticated()
                    onResult(true, null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false, errString.toString())
                }
                // onAuthenticationFailed (a single rejected fingerprint) keeps the prompt open.
            },
        )
    }
}
