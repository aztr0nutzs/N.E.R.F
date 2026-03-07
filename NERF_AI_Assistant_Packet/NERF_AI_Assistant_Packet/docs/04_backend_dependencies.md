# Backend Dependencies and Gaps

## Existing backend/service surfaces likely usable

These should be wrapped where available:

- ScanService
- SpeedtestService
- DevicesService
- DeviceControlService
- RouterControlService
- AnalyticsService
- MapService
- MapTopologyService

## Required assistant-facing data/services

For the full assistant experience, the following must exist or be added:

- public IP provider
- router state readback
- DNS Shield real state and setter
- pause/resume device support
- block/unblock device support
- per-device traffic stats
- Wi-Fi environment / congestion data
- security summary data
- scan state exposure
- speedtest state exposure

## Honest degraded support

If a backend/router capability is unsupported, the assistant must say so directly.

Examples:
- “This router backend does not support pausing clients yet.”
- “Per-device traffic stats are unavailable on this backend.”
- “Wi-Fi congestion analysis requires permission or additional support.”

## Router toggle rule

Do not implement guest Wi-Fi / DNS Shield / VPN / firewall as one-way toggles that only enable. Use explicit setters or state-aware toggles.
