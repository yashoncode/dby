package com.dby.mobile.data

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * App lock's prompt: fingerprint, face or screen lock, API 28+. A successful unlock also
 * opens the locked password key for five minutes (see [Secrets]).
 */
object AppLock {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 28

    suspend fun unlock(activity: Activity, title: String = "Unlock DBY"): Boolean {
        if (!supported) return true
        return suspendCancellableCoroutine { cont ->
            fun done(ok: Boolean) {
                if (cont.isActive) cont.resume(ok)
            }
            val builder = BiometricPrompt.Builder(activity).setTitle(title)
            when {
                Build.VERSION.SDK_INT >= 30 ->
                    builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                Build.VERSION.SDK_INT == 29 -> @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
                else -> builder.setNegativeButton("Cancel", activity.mainExecutor) { _, _ -> done(false) }
            }
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            builder.build().authenticate(
                signal,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
                },
            )
        }
    }
}
