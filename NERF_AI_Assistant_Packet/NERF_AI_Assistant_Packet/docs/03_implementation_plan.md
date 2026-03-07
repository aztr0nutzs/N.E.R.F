# Implementation Plan

## Constraints

- Do not invent fake backend values.
- Do not claim unsupported actions succeeded.
- Do not wire the assistant to UI-only fake functions as source of truth.
- Do not make router toggles one-way enable-only methods.
- Do not collapse the implementation into one giant class.
- Do not break existing Compose screens or HTML theme behavior.

## Phase 1: Foundation

### Deliverables
- assistant package structure created
- core models created
- parser created
- action policy created
- session memory created
- orchestrator skeleton created
- tool registry created
- initial tools created
- ViewModel created
- panel UI host created

### Acceptance
- panel opens/closes
- starter prompts render
- user input submission works
- basic intents resolve
- structured responses render

## Phase 2: Core action paths

### Deliverables
- scan tool wired to real backend
- speedtest tool wired to real backend
- router tool wired for available safe actions
- navigation tool wired
- risky actions routed through confirmation
- unsupported actions return structured unsupported results

### Acceptance
- scan starts from assistant
- speedtest starts/stops/resets from assistant
- navigation commands open correct screens
- guest Wi-Fi / DNS Shield / reboot use confirmation flow
- unsupported backend actions do not fake success

## Phase 3: Context-aware diagnostics

### Deliverables
- context snapshot builder implemented
- diagnosis engine implemented
- recommendation engine implemented
- status and slow-network responses use real context

### Acceptance
- “What’s wrong with my network?” returns context-aware response
- “Run diagnostics” returns ranked findings
- “Why is internet slow?” returns useful explanation tied to real data
- “What should I do next?” returns ordered recommendations

## Phase 4: Entity resolution and follow-ups

### Deliverables
- device/entity resolver implemented
- ambiguity handling implemented
- session memory used for follow-up commands

### Acceptance
- device references resolve correctly
- ambiguous names trigger clarification
- “pause it” works after a recent device reference
- risky device actions never silently target the wrong device

## Phase 5: Polish and auditability

### Deliverables
- assistant action audit events
- richer result cards
- clear unsupported/perms messaging
- suggestion chips after responses
- failure-state polish

### Acceptance
- assistant logs actions and results for debugging
- responses suggest next steps where appropriate
- permission/backend failures are clearly explained
- UI remains stable and responsive
