# Assistant Action Policy

## Safe actions
- scan network
- refresh topology
- run speedtest
- stop speedtest
- reset speedtest
- ping device
- open screen/navigation
- explain metric
- explain feature
- run diagnostics
- recommend next steps

## Risky actions
- reboot router
- block device
- unblock device
- pause device
- resume device
- guest Wi-Fi toggle
- DNS Shield toggle
- future firewall/VPN changes

## Confirmation rules
- risky actions require confirmation
- confirmation must carry the exact resolved intent
- if state changes materially before confirmation, require reconfirmation
- never execute on ambiguous device targets
