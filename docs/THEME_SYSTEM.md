# Theme System (NERF)

NERF supports:
- Native Compose themes (app chrome + native UI)
- HTML theme packs (web parity + in-app WebView preview)

## Stable theme IDs
- nerf_main_dash (html)
- nerf_hud_alt (html)
- neon_nerf (native)
- nerf_dash_new (html)
- nerf_main_hud (html)

Deprecated IDs retained only as migration aliases (not standalone packs):
- speedtest6 -> nerf_dash_new
- nerf_speed2 -> nerf_dash_new

## Locations
- Native: `app/src/main/java/com/nerf/netx/ui/theme/*`
- HTML packs: `app/src/main/assets/themes/<id>/index.html`

## Rules
- Never rename IDs.
- Never delete existing theme packs.
- Theme selection must persist.
