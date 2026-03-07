# Testing Checklist

## Unit tests
- [ ] AssistantIntentParser
- [ ] AssistantActionPolicy
- [ ] AssistantSessionMemory
- [ ] AssistantEntityResolver
- [ ] NetworkDiagnosisEngine
- [ ] RecommendationEngine

## Integration tests
- [ ] ScanTool
- [ ] SpeedtestTool
- [ ] DeviceTool
- [ ] RouterTool
- [ ] Orchestrator intent-to-tool routing

## UI tests
- [ ] panel opens/closes
- [ ] user messages render
- [ ] assistant responses render
- [ ] confirmation prompts render correctly
- [ ] starter prompts work
- [ ] chips/actions work
- [ ] loading/progress states work

## Failure tests
- [ ] backend unavailable
- [ ] unsupported router actions
- [ ] missing permission
- [ ] ambiguous device names
- [ ] stale confirmation intent
- [ ] scan timeout
- [ ] speedtest cancel path
