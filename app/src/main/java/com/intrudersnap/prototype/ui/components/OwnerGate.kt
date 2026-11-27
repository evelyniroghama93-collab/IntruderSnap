package com.intrudersnap.prototype.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intrudersnap.prototype.auth.OwnerAuth
import com.intrudersnap.prototype.auth.OwnerSession
import kotlinx.coroutines.delay

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Asks Android for owner authentication from a composable context. */
fun requestOwnerAuth(context: Context, title: String, subtitle: String, onResult: (Boolean, String?) -> Unit) {
    val activity = context.findActivity()
    if (activity == null) {
        onResult(false, "Authentication needs a visible screen.")
        return
    }
    OwnerAuth.authenticate(activity, title, subtitle, onResult)
}

/**
 * Shows [content] only while the owner session is active; otherwise a lock screen with an
 * "Unlock" button that opens the system authentication prompt (biometric or device PIN).
 */
@Composable
fun OwnerGate(
    what: String,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val active by OwnerSession.active.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    // Apply time-based expiry while visible.
    LaunchedEffect(Unit) {
        while (true) {
            OwnerSession.refresh()
            delay(5_000)
        }
    }

    if (active) {
        content()
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("$what is protected", style = MaterialTheme.typography.titleMedium)
        Text(
            "Confirm it's you with your fingerprint or this phone's screen-lock PIN, pattern or password. " +
                "The session ends when you leave IntruderSnap.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = {
                requestOwnerAuth(context, "Unlock IntruderSnap", what) { ok, msg -> message = if (ok) null else msg }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Unlock") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    }
}
