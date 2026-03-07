# Overview

## Objective

Build and ship a real in-app AI Network Copilot for NERF that is:

- state-aware
- backend-connected
- action-capable
- diagnostic
- safe for router/device actions
- honest about unsupported features
- incrementally extensible

## Version 1 success criteria

The assistant is complete for v1 when it can:

- summarize current network health using real data
- explain key network metrics in plain English
- run real app actions like scan and speedtest
- execute safe control actions through the backend
- require confirmation for risky actions
- diagnose likely causes of common network problems
- recommend next steps based on current conditions
- maintain short-lived conversation context for follow-up commands
- render structured replies rather than plain text only
- degrade gracefully when backend/router support is unavailable

## What this is not

Do not build this as:

- a generic chatbot
- a fake AI wrapper over canned strings
- a frontend-only feature disconnected from backend state
- a system that claims success for unsupported actions

## Core modes

### 1. Status mode
Examples:
- What’s going on with my network?
- Is anything wrong?
- How many devices are online?

### 2. Diagnostic mode
Examples:
- Run diagnostics
- Why is my internet slow?
- Why is gaming lagging?

### 3. Action mode
Examples:
- Scan network
- Run speed test
- Pause that device
- Turn on guest Wi-Fi

### 4. Explanation mode
Examples:
- What is jitter?
- What is packet loss?
- What is DNS Shield?

### 5. Guided remediation mode
Examples:
- What should I do next?
- Optimize my network
- Fix what you can

## Real differentiator

The assistant should feel like a network copilot, not a chat widget. The value comes from:

- understanding current state
- explaining issues clearly
- recommending the next best action
- executing safe actions directly
- escalating to structured diagnostics when needed
