Continue the NERF AI Assistant implementation.

Now implement PHASE 3 CONTEXT-AWARE DIAGNOSTICS + PHASE 4 ENTITY RESOLUTION.

Tasks:
1. Add AssistantEntityResolver that can resolve device references from:
   - hostname
   - nickname
   - IP
   - vendor
   - fuzzy name match
2. Add ambiguity handling so the assistant returns structured clarification responses with selectable device candidates instead of guessing.
3. Implement NetworkDiagnosisEngine using AssistantContextSnapshot with initial rule-based findings for:
   - ISP/WAN outage
   - local router/network reachability issues
   - high latency
   - high jitter
   - packet loss
   - weak Wi-Fi signal
   - same-channel congestion
   - high-throughput hog device
   - unknown device risk
4. Implement RecommendationEngine that converts diagnosis/context into ordered next-step recommendations.
5. Extend AssistantResponseComposer to render:
   - diagnosis cards
   - issue severity
   - evidence bullets
   - suggested follow-up actions
6. Extend AssistantOrchestrator to support:
   - “What’s wrong with my network?”
   - “Why is my internet slow?”
   - “Why is latency high?”
   - “What should I do next?”
   - device-specific diagnostic follow-ups where a device is resolved
7. Add tests for:
   - entity resolution
   - ambiguity handling
   - diagnosis rules
   - recommendation generation
8. Keep all unsupported backend capabilities explicit and honest.

At the end, summarize:
- diagnosis behaviors implemented
- entity resolution behaviors implemented
- remaining backend dependencies still needed for full assistant quality
