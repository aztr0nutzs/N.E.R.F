Build the first implementation pass for Network Doctor using the same context, diagnosis, and recommendation infrastructure created for the NERF AI Assistant.

Goals:
- Create a structured diagnostics screen
- Reuse AssistantContextSnapshot
- Reuse NetworkDiagnosisEngine
- Reuse RecommendationEngine
- Present ranked issues, explanations, and one-tap safe actions

Requirements:
1. Create a dedicated Network Doctor screen using existing app patterns.
2. Show current health summary.
3. Show ranked issues with severity and confidence.
4. Show evidence for each diagnosis.
5. Show ordered next steps.
6. Offer safe one-tap actions first.
7. Mark risky actions as requiring confirmation.
8. Keep all unsupported backend capabilities explicit and honest.
