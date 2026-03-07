package com.nerf.netx.networkdoctor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nerf.netx.assistant.model.AssistantSeverity
import com.nerf.netx.networkdoctor.model.NetworkDoctorAction
import com.nerf.netx.networkdoctor.model.NetworkDoctorActionItem
import com.nerf.netx.networkdoctor.model.NetworkDoctorEvidenceUi
import com.nerf.netx.networkdoctor.model.NetworkDoctorFindingUi
import com.nerf.netx.networkdoctor.model.NetworkDoctorHealthSummary
import com.nerf.netx.networkdoctor.model.NetworkDoctorRecommendationUi
import com.nerf.netx.networkdoctor.model.NetworkDoctorUnavailableUi

@Composable
fun HealthSummaryCard(
  summary: NetworkDoctorHealthSummary,
  onRefresh: () -> Unit
) {
  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
          Text(summary.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(summary.message, style = MaterialTheme.typography.bodySmall)
        }
        SeverityBadge(summary.severity, label = "Score ${summary.score}")
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricPill("Issues ${summary.issueCount}")
        MetricPill("Alerts ${summary.activeAlertCount}")
        MetricPill(summary.status.name)
      }
      OutlinedButton(onClick = onRefresh) { Text("Refresh Doctor") }
    }
  }
}

@Composable
fun DiagnosisFindingCard(
  item: NetworkDoctorFindingUi,
  onAction: (NetworkDoctorAction) -> Unit
) {
  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(item.summary, style = MaterialTheme.typography.bodySmall)
        }
        SeverityBadge(item.severity, item.category.name.replace("_", " "))
      }
      item.evidence.forEach { line ->
        Text("- $line", style = MaterialTheme.typography.bodySmall)
      }
      if (item.inferred) {
        Text("Inference only: direct backend proof is unavailable.", style = MaterialTheme.typography.labelSmall)
      }
      ActionRow(item.actions, onAction)
    }
  }
}

@Composable
fun RecommendationCard(
  item: NetworkDoctorRecommendationUi,
  onAction: (NetworkDoctorAction) -> Unit
) {
  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        SeverityBadge(item.severity, item.category.name.replace("_", " "))
      }
      Text(item.rationale, style = MaterialTheme.typography.bodySmall)
      item.action?.let { ActionRow(listOf(it), onAction) }
    }
  }
}

@Composable
fun UnavailableDataCard(item: NetworkDoctorUnavailableUi) {
  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        MetricPill(item.category.name.replace("_", " "))
      }
      Text(item.message, style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
fun CurrentEvidenceCard(item: NetworkDoctorEvidenceUi) {
  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.label, style = MaterialTheme.typography.titleSmall)
        Text(item.value, style = MaterialTheme.typography.bodySmall)
      }
      SeverityBadge(item.severity, item.category.name.replace("_", " "))
    }
  }
}

@Composable
fun SectionHeader(title: String) {
  Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun ActionRow(
  actions: List<NetworkDoctorActionItem>,
  onAction: (NetworkDoctorAction) -> Unit
) {
  if (actions.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      actions.forEach { action ->
        if (action.prominent) {
          Button(onClick = { onAction(action.action) }, enabled = action.enabled) { Text(action.label) }
        } else {
          OutlinedButton(onClick = { onAction(action.action) }, enabled = action.enabled) { Text(action.label) }
        }
      }
    }
    actions.firstOrNull { !it.enabled && !it.unavailableReason.isNullOrBlank() }?.let { disabledAction ->
      Text("Unsupported: ${disabledAction.unavailableReason.orEmpty()}", style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun SeverityBadge(severity: AssistantSeverity, label: String) {
  val color = when (severity) {
    AssistantSeverity.INFO -> Color(0xFF4AA3FF)
    AssistantSeverity.SUCCESS -> Color(0xFF4DD387)
    AssistantSeverity.WARNING -> Color(0xFFFFB347)
    AssistantSeverity.ERROR -> Color(0xFFFF6B6B)
  }
  Box(
    modifier = Modifier
      .background(color.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp)
  ) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
  }
}

@Composable
private fun MetricPill(label: String) {
  Box(
    modifier = Modifier
      .background(Color(0x1F7CA4C9), RoundedCornerShape(999.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp)
  ) {
    Text(label, style = MaterialTheme.typography.labelSmall)
  }
}
