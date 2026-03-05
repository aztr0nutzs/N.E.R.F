# AGENTS.md (NERF)

Rules for any AI agent working on NERF.

## Non-negotiables
- Android-first (Compose). Web second.
- Theme choice is permanent and must never be removed.
- HTML theme packs under `assets/themes/*` are required and must never be deleted or renamed.
- Mobile-first: no horizontal overflow or clipped UI.
- UI actions call interfaces to keep backend wiring deterministic.

## Done means
- Builds
- No cutoffs on a ~360dp wide device
- Theme selector works
- HTML packs load in WebView preview
