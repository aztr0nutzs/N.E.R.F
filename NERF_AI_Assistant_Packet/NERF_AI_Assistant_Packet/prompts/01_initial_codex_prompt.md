You are implementing the NERF AI Assistant as a real backend-connected network copilot inside the existing Android app.

Goal:
Build the assistant foundation in a clean, extensible, production-ready way. This is not a generic chatbot. It must be a structured assistant that can understand intent, use real app/backend data, invoke real tools/actions, require confirmation for risky operations, and render structured responses.

Constraints:
- Do not invent fake backend values.
- Do not claim unsupported actions succeeded.
- Do not remove existing MP4s, animations, or dashboard visuals unless necessary for correct integration.
- Do not collapse everything into one class.
- Follow existing project structure and naming where possible.
- Keep changes deterministic and build-safe.

Phase to implement now:
PHASE 1 FOUNDATION + PHASE 2 CORE ACTION PATHS

Tasks:
1. Inspect the project structure and identify the correct package paths for assistant-related code.
2. Create a new assistant package structure with subpackages for:
   - model
   - parser
   - orchestrator
   - tools
   - diagnostics
   - recommendation
   - state
   - ui
3. Add the following core models:
   - AssistantIntent
   - AssistantResponse
   - AssistantToolResult
   - AssistantContextSnapshot
   - AssistantUiState
   - supporting enums/data classes for severity, cards, suggested actions, loading state, and destinations
4. Implement a deterministic AssistantIntentParser that supports these initial intents:
   - network status summary
   - run diagnostics
   - scan network
   - start speedtest
   - stop speedtest
   - reset speedtest
   - refresh topology
   - open speedtest/devices/map/analytics
   - explain metric
   - explain feature
   - set guest wifi on/off
   - set dns shield on/off
   - reboot router
   - flush dns
   - ping device
   - block device
   - unblock device
   - pause device
   - resume device
5. Implement an AssistantActionPolicy that marks risky actions and requires confirmation for:
   - reboot router
   - block/unblock device
   - pause/resume device
   - guest wifi toggle
   - dns shield toggle
6. Create AssistantSessionMemory for short-lived in-session context:
   - last discussed device
   - last pending confirmation intent
   - recent actions
7. Create AssistantToolRegistry and initial tool wrappers over existing services where available:
   - ScanTool
   - SpeedtestTool
   - DeviceTool
   - RouterTool
   - NavigationTool
8. Create BuildAssistantContextUseCase that aggregates the currently available app state into AssistantContextSnapshot. If some data is not yet available, return null/empty safely and do not fake values.
9. Create AssistantResponseComposer that converts tool/context results into structured assistant responses with:
   - title
   - message
   - severity
   - suggested actions
   - optional cards
10. Create AssistantOrchestrator that:
   - accepts a user message
   - parses intent
   - resolves whether confirmation is needed
   - calls tools or context builder
   - returns AssistantResponse
   - returns clarification responses for ambiguous/missing device targets instead of guessing
11. Create AssistantViewModel and initial UI state flow.
12. Add an initial AssistantPanel UI host (Compose preferred if the app already uses Compose for main surfaces). It must support:
   - starter prompts
   - message list
   - user message input
   - structured assistant responses
   - confirmation prompt rendering
13. Wire the panel into the app in the least risky way without breaking current navigation or existing screens.
14. Add unit tests for:
   - AssistantIntentParser
   - AssistantActionPolicy
   - AssistantSessionMemory basic behavior
15. Keep implementation honest: unsupported tool actions must return structured unsupported responses, not fake success.

Implementation notes:
- Reuse existing service interfaces and backend gateway where possible.
- If a device action requires backend support that is not implemented yet, wrap it as unsupported and expose that clearly to the assistant layer.
- Prefer file-by-file additions over broad invasive rewrites.
- At the end, provide a concise summary of:
   - files added
   - files modified
   - what is fully working
   - what is stubbed or unsupported
   - what Phase 3 should implement next
