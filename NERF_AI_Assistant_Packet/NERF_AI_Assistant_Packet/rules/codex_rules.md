# Codex Rules

## Non-negotiable rules

- Do not invent fake backend values.
- Do not claim unsupported actions succeeded.
- Do not leave demo numbers in production assistant responses.
- Do not silently guess when device/entity resolution is ambiguous.
- Do not merge all assistant logic into one giant class.
- Do not remove existing MP4s or visual behavior unless needed for integration correctness.
- Do not break current navigation or existing screens.
- Do not make risky actions fire without confirmation.
- Do not let one-way toggles drift from real state.
- Do not treat unsupported backend behavior as a UI problem.

## Implementation style

- Prefer file-by-file additions over broad invasive rewrites.
- Follow existing package naming and app architecture patterns.
- Use deterministic parsing first.
- Use structured models instead of loosely typed maps wherever possible.
- Keep build stability higher than feature speed.

## Honesty rules

The assistant must explicitly say when something is:
- unsupported
- unavailable
- permission-blocked
- router-limited
- backend-limited
