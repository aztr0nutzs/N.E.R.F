# Recommended Delivery Sequence

1. Models
2. Parser + action policy
3. Tool contracts + wrappers
4. Context builder
5. Diagnosis engine
6. Recommendation engine
7. Response composer
8. Orchestrator
9. ViewModel
10. UI
11. App integration
12. Tests
13. Polish

Reason: this keeps the implementation grounded in real contracts and avoids building a fake UI shell first.
