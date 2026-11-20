package com.intrudersnap.prototype.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intrudersnap.prototype.domain.StatusLabel
import com.intrudersnap.prototype.domain.TestOutcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF1E8E3E)
private val Amber = Color(0xFFB06000)
private val Grey = Color(0xFF5F6368)
private val Red = Color(0xFFC5221F)
private val Purple = Color(0xFF6A1B9A)

fun StatusLabel.color(): Color = when (this) {
    StatusLabel.SUPPORTED -> Green
    StatusLabel.PERMISSION_REQUIRED -> Amber
    StatusLabel.NOT_YET_TESTED -> Grey
    StatusLabel.UNSUPPORTED -> Purple
    StatusLabel.TEST_FAILED -> Red
}

fun TestOutcome.color(): Color = when (this) {
    TestOutcome.PASS -> Green
    TestOutcome.FAIL -> Red
    TestOutcome.BLOCKED -> Purple
    TestOutcome.INCONCLUSIVE -> Amber
    TestOutcome.NOT_TESTED -> Grey
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun StatusChip(status: StatusLabel) = Pill(status.label, status.color())

@Composable
fun OutcomeChip(outcome: TestOutcome) = Pill(outcome.label, outcome.color())

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
    }
}

@Composable
fun StatusRow(label: String, status: StatusLabel, detail: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            StatusChip(status)
        }
        if (!detail.isNullOrBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun formatTime(ms: Long): String =
    if (ms <= 0L) "Never" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
