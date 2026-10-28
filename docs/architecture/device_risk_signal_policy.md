# Device Risk Signal Policy

Status: internal, pure policy only. No running producer authenticates institution and device identity for this path;
the observation store and this policy are not wired into scoring. `AUTHENTICATED_INSTITUTION` in a record is a
claim made by its creator, not proof of authentication. A future admission boundary must independently prove and
bind that claim before any result may be used.

Policy version `1` interprets **only** a complete, sealed, pre-occurrence 30-day history within one institution,
source, customer and versioned-device scope. The observation's `asOf` is the occurrence event time; `windowStart`
must equal `asOf - 30 days`, coverage must reach `asOf`, and the source observation time must not be in the future
relative to `asOf`. A count of zero means only a bounded absence in the complete history window. Equal-time, late,
missing, evicted, key-rotation, and capacity-limited histories cannot be silently counted as zero.
`DeviceRiskEvidence` validates structure but does not establish the missing producer trust boundary.

| Signal in fixed output order | Support | Predicate and required proof | Outcome and fixed diagnostic reason codes | Interpretation |
| --- | --- | --- | --- | --- |
| Device not seen recently | `SUPPORTED` | `priorDevice.count == 0`; authenticated binding and full 30-day prior history required | `AVAILABLE` + `DEVICE_NOVELTY_NOT_SEEN_IN_WINDOW` or `DEVICE_NOVELTY_SEEN_IN_WINDOW`; otherwise `UNAVAILABLE`, `UNTRUSTED`, or `INVALID` evidence with a matching fixed reason code | Bounded absence only; never first-ever or fraud. |
| Device switch | `SUPPORTED` | `switches.count > 0`; full prior history plus the transition into the current event. Ambiguous equal-time transition is `UNAVAILABLE` | `AVAILABLE` + `DEVICE_SWITCH_OBSERVED` or `DEVICE_SWITCH_NOT_OBSERVED`; otherwise `UNAVAILABLE`, `UNTRUSTED`, or `INVALID` evidence with a matching fixed reason code | Count covers transitions in `[t-30d,t)` plus current transition; no threshold or fraud verdict. |
| Other customers / shared device | `UNSUPPORTED` | No verified scoped identifier or authorized cross-customer join exists | Always `UNAVAILABLE`; `DEVICE_SHARED_DEVICE_UNSUPPORTED_SOURCE` (or the untrusted-source variant) | Matching client strings do not prove the same physical device. |
| Device country mismatch | `UNSUPPORTED` | Requires an independent, fresh device-country provider and an authorized reference-country policy; existing transaction-home `COUNTRY_MISMATCH` is not device geography | Always `UNAVAILABLE`; `DEVICE_COUNTRY_MISMATCH_UNSUPPORTED_SOURCE` (or the untrusted-source variant) | A missing provider or a structurally supplied country never becomes a negative result. |
| Proxy or VPN | `UNSUPPORTED` | Requires an independently bounded detector assertion bound to provider, time and subject; request booleans are not evidence | Always `UNAVAILABLE`; `DEVICE_PROXY_OR_VPN_UNSUPPORTED_SOURCE` (or the untrusted-source variant) | `false`, `true`, and missing caller values all remain unsupported. |

History failures use fixed reason codes such as `DEVICE_NOVELTY_HISTORY_INCOMPLETE`,
`DEVICE_SWITCH_HISTORY_UNAVAILABLE`, `DEVICE_SWITCH_HISTORY_INVALID`, and signal-specific
`*_UNTRUSTED_SOURCE`. Client-asserted and synthetic top-level sources yield `SKIPPED` and `UNTRUSTED` evidence
state for every signal. Each outcome separately exposes support (`SUPPORTED`/`UNSUPPORTED`), evidence state
(`AVAILABLE`/`UNAVAILABLE`/`UNTRUSTED`/`INVALID`/`UNSUPPORTED`), predicate observation when valid, and a
fixed non-sensitive reason code. No user-supplied text, identifier, IP, location, commitment, or raw detector value
appears in diagnostic output. Signal order is deterministic; the policy ranks no signals by severity. It does not
reuse the existing Rules `DEVICE_NOVELTY` code or weights as an uncalibrated verdict.

Switch-count temporal semantics are recorded in the [Device Risk switch semantics ADR](../adr/device_risk_switch_semantics.md).

The operating status is `DEGRADED` whenever one or both supported history predicates are available, because there is
no calibrated Device Risk score; it is `UNAVAILABLE` when no supported predicate can be evaluated, and `SKIPPED`
for client/synthetic top-level input. Source quality (`AUTHENTICATED_INSTITUTION`, `CLIENT_ASSERTED`, or `SYNTHETIC`)
is carried separately; `AUTHENTICATED_INSTITUTION` remains a claim until an independent admission boundary verifies
it. The internal `EvidenceCoverage` value describes only the two supported history predicates:
`NO_SUPPORTED_HISTORY`, `PARTIAL_SUPPORTED_HISTORY`, or `COMPLETE_SUPPORTED_HISTORY`. The three unsupported
signals remain `UNAVAILABLE`, including when both history predicates are available. This coverage value is not a
fraud-risk confidence. The mapped `FraudSignalEvaluation.confidence` stays `UNKNOWN`, while `riskInference` is
explicitly `NOT_CALIBRATED` and `score` and risk level remain null. A future calibration gate requires measured
false-positive rates, source reliability, population sensitivity, a reviewed threshold, and a separate model-risk
approval.
Repeated client values, shared workstations, device resets, travel, and incomplete recovery can all make a pattern
misleading. No isolated signal changes Rules/ML score, alert decision, or payment outcome.

`DeviceRiskSignalEvaluationMapper` keeps the five ordered reason codes and fixed descriptions in
`FraudSignalEvaluation`; the aggregation sanitizer applies existing length/count and safe-text bounds, and the
current public contract accepts only the fixed Device Risk code allowlist. The identity registry permits this optional
fourth diagnostic engine, but the candidate is not registered in the running engine set. The engine has no evidence
reader and emits only `DEVICE_EVIDENCE_NOT_ADMITTED` until independently bound evidence exists. Public diagnostics
therefore do not mean a risk inference is available or calibrated.

## Internal engine staging

`DeviceRiskSignalEngine` has the candidate descriptor `device.primary` / `DEVICE_RISK` / `java` / `device-v1`
with `required=false`. It is not a Spring bean or a member of `FraudSignalEngineRegistry`. `fraud.scoring.engines.device.enabled` defaults to
`false`, and setting it to `true` fails startup. The candidate has no evidence-reader dependency and always
returns `UNAVAILABLE` / `DEVICE_EVIDENCE_NOT_ADMITTED`, with one bounded operational explanation.

`ScoringContext` is built from the enriched Kafka event and has no independently authenticated institution,
source, transaction-occurrence, sequence, key epoch, erasure generation, or immutable snapshot commitment.
Its transaction and correlation IDs cannot serve as trusted binding values. The former reader API that returned
an arbitrary `Optional<DeviceRiskEvidence>` has been removed, so a structurally valid record labelled
`AUTHENTICATED_INSTITUTION` cannot reach this engine. The pure policy remains separately testable, but the
candidate engine does not invoke it. A future reader must compare every persisted occurrence attribute and
commitment against values supplied by a separately verified authority; this repository has no such authority,
so the evidence-consumption contract and engine remain disabled.

This candidate does not publish a fourth result and cannot affect Rules/ML behavior. Any future lawful
observation collection needs its own explicit gate, independently of scoring-engine activation.
