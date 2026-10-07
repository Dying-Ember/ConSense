# LLM source selection tickets

Integration: feature/drafting-a-business-rules-20261005 in each isolated repository.
Tracker: docs/agents/issue-tracker.md. Spec: docs/specs/llm-source-selection.md.

| Ticket | Scope | Blocks on | Status | Verification |
| --- | --- | --- | --- | --- |
| A14 | Immutable chat profiles, safe API, Drafting/Vetting routing and identity | none | resolved | [A14](verification/a14.md), independent merge 25176ffd; accepted through [A16](verification/a16.md) |
| A14b | Advice routing, explicit outage and persisted answer identity | A14 shared API 259a669553c183cfa7078c0558823c687909b1e5 | resolved | [A14b](verification/a14b.md), independent merge c358fc267049c0d555e5b863b3ae0ea18006b44a; accepted through A16 |
| A15 | Shared trilingual selector, browser persistence and operation-entry snapshots | none; shared contract frozen | resolved | [A15](verification/a15.md), independent web merge 654b7651b4a5a1324a7555cd38afe4b35d15672d; accepted through A16 |
| A16 | Independent merge/review, owned preview, both actual provider smoke and IAB checks | A14, A14b, A15 | resolved for bounded scope | [A16](verification/a16.md); source identity/restart/UI passed; concurrent pre-deployment demo extraction and failed exact initial-state comparison retained explicitly |

Prior A09/A06 native-browser download gates remain pending. Do not close them through unrelated source-switch checks.


