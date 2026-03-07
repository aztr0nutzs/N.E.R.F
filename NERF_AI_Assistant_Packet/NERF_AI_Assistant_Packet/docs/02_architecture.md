# Architecture

## High-level layers

### Layer A: Assistant UI Layer
Responsible for:
- panel open/close
- chat history rendering
- quick actions
- suggestion chips
- progress indicators
- confirmation prompts
- result cards
- error banners

### Layer B: Assistant ViewModel / Presentation Layer
Responsible for:
- conversation state
- dispatching requests to orchestrator
- loading/progress/confirmation state
- exposing render-ready UI state

### Layer C: Assistant Orchestrator Layer
Responsible for:
- intent resolution
- entity resolution
- confirmation routing
- tool invocation
- building structured assistant responses

### Layer D: Assistant Tool Layer
Responsible for:
- wrapping backend/service calls
- normalizing tool results
- mapping unsupported backend cases into explicit assistant-safe failures

### Layer E: Assistant Intelligence Layer
Responsible for:
- diagnosis
- recommendation generation
- plain-language response composition
- contextual follow-up suggestions

## Required package structure

Create:

- `assistant/model`
- `assistant/orchestrator`
- `assistant/parser`
- `assistant/tools`
- `assistant/diagnostics`
- `assistant/recommendation`
- `assistant/ui`
- `assistant/state`

## Core components

### Models
- AssistantIntent
- AssistantResponse
- AssistantToolResult
- AssistantContextSnapshot
- AssistantUiState

### Parsing / Resolution
- AssistantIntentParser
- AssistantEntityResolver
- AssistantFollowUpResolver

### Orchestration
- AssistantOrchestrator
- AssistantActionPolicy

### Tools
- AssistantToolRegistry
- ScanTool
- SpeedtestTool
- DeviceTool
- RouterTool
- SecurityTool
- WifiEnvironmentTool
- TopologyTool
- NavigationTool
- DiagnosticsTool

### Intelligence
- NetworkDiagnosisEngine
- RecommendationEngine
- AssistantResponseComposer

### Context / State
- BuildAssistantContextUseCase
- AssistantSessionMemory

### UI
- AssistantViewModel
- AssistantPanel
- AssistantMessageCard
- AssistantQuickActions
- AssistantConfirmationDialog

## Context aggregation

The assistant should not make scattered ad hoc backend calls whenever possible. Build one unified context snapshot.

### Target snapshot contents
- network status
- router status
- connection type
- public IP
- speedtest state / latest result
- device list
- known vs unknown devices
- suspicious devices
- blocked/paused devices
- scan state
- topology summary
- Wi-Fi congestion / environment
- security summary
- active alerts

