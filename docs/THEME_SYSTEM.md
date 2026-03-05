# Theme System (NERF)

NERF supports:
- Native Compose themes (app chrome + native UI)
- HTML theme packs (web parity + in-app WebView preview)

## Stable theme IDs
- nerf_main_dash (html)
- nerf_hud_alt (html)
- neon_nerf (native)
- speedtest6 (html)
- nerf_speed2 (html)

## Locations
- Native: `app/src/main/java/com/nerf/netx/ui/theme/*`
- HTML packs: `app/src/main/assets/themes/<id>/index.html`

## Rules
- Never rename IDs.
- Never delete existing theme packs.
- Theme selection must persist.
