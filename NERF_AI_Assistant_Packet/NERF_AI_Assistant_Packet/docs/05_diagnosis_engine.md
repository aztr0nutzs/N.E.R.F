# Diagnosis Engine Plan

## Purpose

Build a rule-based diagnosis engine that inspects current network/app state and returns ranked findings.

## Input
- AssistantContextSnapshot

## Output
- ranked DiagnosisFinding list

## DiagnosisFinding fields
- id
- title
- severity
- confidence
- explanation
- evidence
- suggestedActions

## Initial rule set

### Connectivity
- no WAN + router reachable + LAN present -> likely ISP/WAN outage
- no internet + no router reachability -> possible local router/network failure

### Latency
- latency > 100 ms -> degraded experience
- high jitter + low packet loss -> unstable route or congestion
- high jitter + high packet loss -> likely weak Wi-Fi or interference

### Throughput
- low throughput + strong signal -> possible ISP bottleneck or QoS saturation
- one device dominating throughput -> likely local bandwidth hog
- good download + poor upload -> uplink bottleneck

### RF / Wi-Fi
- many same-channel neighbors -> congestion
- weak signal + poor speed -> distance or interference
- target device stuck on congested band -> recommend better band/channel

### Security
- unknown device active -> review/block candidate
- high-throughput unknown device -> elevated concern
- multiple unknown devices after scan -> security review recommended

### Backend / Router
- repeated router action failures -> backend degraded or unsupported
- repeated scan failures -> permissions/backend issue

## Priority

Findings should be ordered by severity, confidence, and practical usefulness.
