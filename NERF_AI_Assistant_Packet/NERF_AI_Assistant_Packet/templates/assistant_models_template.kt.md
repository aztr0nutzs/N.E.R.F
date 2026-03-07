# Kotlin Model Template Reference

```kotlin
sealed interface AssistantIntent {
    data object NetworkStatusSummary : AssistantIntent
    data object SecuritySummaryIntent : AssistantIntent
    data object RunDiagnostics : AssistantIntent
    data object RecommendNextSteps : AssistantIntent
    data class ExplainMetric(val metricKey: String) : AssistantIntent
    data class ExplainFeature(val featureKey: String) : AssistantIntent

    data class ScanNetwork(val deep: Boolean = true) : AssistantIntent
    data object StartSpeedtest : AssistantIntent
    data object StopSpeedtest : AssistantIntent
    data object ResetSpeedtest : AssistantIntent
    data object RefreshTopology : AssistantIntent

    data class PingDevice(val deviceId: String) : AssistantIntent
    data class BlockDevice(val deviceId: String) : AssistantIntent
    data class UnblockDevice(val deviceId: String) : AssistantIntent
    data class PauseDevice(val deviceId: String) : AssistantIntent
    data class ResumeDevice(val deviceId: String) : AssistantIntent

    data class SetGuestWifi(val enabled: Boolean) : AssistantIntent
    data class SetDnsShield(val enabled: Boolean) : AssistantIntent
    data class RebootRouter(val confirmed: Boolean = false) : AssistantIntent
    data object FlushDns : AssistantIntent

    data class DiagnoseSlowInternet(val targetDeviceId: String? = null) : AssistantIntent
    data class DiagnoseHighLatency(val targetDeviceId: String? = null) : AssistantIntent
    data class DiagnoseUnknownDevice(val deviceId: String? = null) : AssistantIntent

    data class NavigateTo(val destination: AssistantDestination) : AssistantIntent
}

data class AssistantResponse(
    val messageId: String,
    val title: String?,
    val message: String,
    val severity: AssistantSeverity,
    val cards: List<AssistantCard> = emptyList(),
    val suggestedActions: List<AssistantSuggestedAction> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val confirmationIntent: AssistantIntent? = null,
    val loadingState: AssistantLoadingState? = null,
    val metadata: AssistantResponseMetadata? = null
)

data class AssistantToolResult<T>(
    val success: Boolean,
    val data: T? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val isUnsupported: Boolean = false
)
```
