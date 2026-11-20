package com.intrudersnap.prototype.ui.results

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intrudersnap.prototype.domain.AcceptanceItem
import com.intrudersnap.prototype.domain.AcceptanceResult
import com.intrudersnap.prototype.domain.TestOutcome
import com.intrudersnap.prototype.ui.components.OutcomeChip
import com.intrudersnap.prototype.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun ResultsScreen(vm: ResultsViewModel = viewModel()) {
    val results by vm.results.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "PASS is only shown when the evidence is in the event history. Without evidence an item stays NOT TESTED. " +
                    "If you performed a test and nothing was recorded, mark it FAIL. These results describe THIS device " +
                    "only. State in the report whether it is a physical device or an emulator.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(results, key = { it.item.name }) { r ->
            ResultCard(r, onToggleFail = { vm.setMarkedFailed(r.item, !r.markedByTester) })
        }
        item {
            Button(
                onClick = {
                    scope.launch {
                        val report = vm.buildReport()
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "IntruderSnap Milestone 1 test report")
                            .putExtra(Intent.EXTRA_TEXT, report)
                        context.startActivity(Intent.createChooser(send, "Share test report"))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share test report (text)") }
        }
    }
}

@Composable
private fun ResultCard(r: AcceptanceResult, onToggleFail: () -> Unit) {
    SectionCard(r.item.title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutcomeChip(r.outcome)
            if (r.markedByTester) {
                Text("  marked by tester", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(r.evidence, style = MaterialTheme.typography.bodyMedium)
        Text("How to test: ${r.item.howToTest}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Background camera outcomes come only from test E; the tester cannot override them here.
        if (r.item != AcceptanceItem.BACKGROUND_CAMERA && (r.outcome != TestOutcome.PASS || r.markedByTester)) {
            TextButton(onClick = onToggleFail) {
                Text(if (r.markedByTester) "Clear FAIL mark" else "Mark FAIL (test performed, no evidence)")
            }
        }
    }
}
