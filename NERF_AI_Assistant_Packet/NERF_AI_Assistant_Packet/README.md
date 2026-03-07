# NERF AI Assistant Packet

This packet packages the NERF AI Assistant planning work into a clean, Codex-ready folder structure.

## Contents

- `docs/`
  - Product definition, architecture, implementation plan, backend gap analysis, and rollout guidance.
- `prompts/`
  - Ready-to-paste prompts for Codex to start and continue implementation.
- `rules/`
  - Guardrails and engineering rules to keep the build honest and stable.
- `checklists/`
  - Delivery checklist, acceptance gates, and testing checklist.
- `templates/`
  - Kotlin model templates and package skeleton references.

## Recommended order

1. Read `docs/01_overview.md`
2. Read `docs/02_architecture.md`
3. Read `docs/03_implementation_plan.md`
4. Read `rules/codex_rules.md`
5. Start with `prompts/01_initial_codex_prompt.md`
6. Validate against `checklists/phase_acceptance_gates.md`
7. Continue with `prompts/02_followup_codex_prompt.md`

## Goal

Build a real in-app AI Network Copilot for NERF that:

- understands network/app state
- executes real actions safely
- explains problems clearly
- diagnoses issues using actual data
- stays honest about unsupported backend features

## Notes

This packet is planning and implementation guidance only. It does not include generated Kotlin source files for the app itself.
