# Device Risk Signal Policy

Status: internal, pure policy only. No running producer authenticates institution and device identity for this path;
the observation store and this policy are not wired into scoring. `AUTHENTICATED_INSTITUTION` in a record is a
claim made by its creator, not proof of authentication. A future admission boundary must independently prove and
bind that claim before any result may be used.

Policy version `1` interprets **only** a complete, sealed, pre-occurrence 30-day history within one authenticated
institution/customer/device scope. The observation's `asOf` is the occurrence event time; `windowStart` must equal
`asOf - 30 days`, coverage must reach `asOf`, and the source observation time must not be in the future relative to
`asOf`. A count of zero has meaning only under that full proof. Equal-time, late, missing, evicted, key-rotation, and
capacity-limited histories cannot be silently counted as zero. `DeviceRiskEvidence` validates structure but does not
establish the missing producer trust boundary.

| Signal in fixed output order | Predicate when admissible | Required proof and failure result | Local reason and confidence | Interpretation |
| --- | --- | --- | --- | --- |
| Device not seen recently | `priorDevice.count == 0` | Authenticated binding and full 30-day prior history; otherwise `UNAVAILABLE` | `NOT_SEEN_IN_WINDOW` or `SEEN_IN_WINDOW`; `MEDIUM` when evaluated | Absence in this window, never first-ever or fraud. |
| Device switch | `switches.count > 0` | Same complete history with deduplicated, event-time ordered switches; otherwise `UNAVAILABLE` | `SWITCH_OBSERVED` or `NO_SWITCH_OBSERVED`; `MEDIUM` when evaluated | One or more observed transitions, not a risk threshold. |
| Other customers | No admitted predicate | Verified physical-device linking across authenticated customers is absent; always `UNAVAILABLE` | `UNSUPPORTED_SOURCE`; `UNKNOWN` | Matching client strings do not prove a shared device. |
| Device country mismatch | No admitted predicate | Trusted device geolocation and reference-country policy are absent; always `UNAVAILABLE` | `UNSUPPORTED_SOURCE`; `UNKNOWN` | Existing Rules `COUNTRY_MISMATCH` compares different facts. |
| Proxy or VPN | No admitted predicate | Trusted peer origin and detector assertion are absent; always `UNAVAILABLE` | `UNSUPPORTED_SOURCE`; `UNKNOWN` | Request booleans are not detector evidence. |

History failures use a fixed, bounded reason vocabulary: `HISTORY_INCOMPLETE` for a short or late window,
`HISTORY_UNAVAILABLE` for absent/key/capacity data, and `HISTORY_INVALID` for rejected provenance or time.
Client-asserted and synthetic sources yield `SKIPPED` and `UNTRUSTED_SOURCE` for every signal. No user-supplied
text, identifier, IP, location, or raw detector value appears in the result. Signal order above is the deterministic
tie rule; the policy ranks no signals by severity. It does not export or extend public `ReasonCode`. In particular,
the existing `DEVICE_NOVELTY` code and Rules weights are not reused for an uncalibrated 30-day absence.

The aggregate status is `AVAILABLE` only when both supported history signals evaluate, `DEGRADED` when one does,
`UNAVAILABLE` when neither does, and `SKIPPED` for client/synthetic input. Aggregate confidence is respectively
`MEDIUM`, `LOW`, or `UNKNOWN`; these describe evidence coverage, **not** probability of fraud. There is no numeric
score or HIGH/CRITICAL risk level in version 1. `score` is null in every result until measured false-positive rates,
source reliability, population sensitivity, and a calibrated threshold are available and reviewed. Repeated client
tokens, shared workstations, device resets, travel, and incomplete recovery can all make the apparent pattern
misleading. No isolated signal changes the Rules/ML score, alert decision, or payment outcome.

## Internal engine staging

`DeviceRiskSignalEngine` has the candidate descriptor `device.primary` / `DEVICE_RISK` / `java` / `device-v1`
with `required=false`. It has no production `AdmittedDeviceRiskEvidenceReader` and is not a Spring bean or a
member of `FraudSignalEngineRegistry`; the current three-engine public contract still rejects its identity.
`fraud.scoring.engines.device.enabled` defaults to `false`. Setting it to `true` fails startup until an
occurrence-bound authenticated reader and a migrated public contract are proved. The flag does not start an
observation consumer or mutate history; any future lawful observation collection needs its own explicit gate.

The pure policy's `AVAILABLE` means the two supported historical predicates were evaluated. The present
`FraudEngineResult` contract uses `AVAILABLE` only with a calibrated score and risk level. Therefore the candidate
engine maps complete policy facts to `DEGRADED` with `DEVICE_SCORE_NOT_CALIBRATED` and no score, public reason,
or risk level. Partial facts are also `DEGRADED`; absent facts are `UNAVAILABLE`; untrusted sources are `SKIPPED`.
This mapping is internal and does not publish a fourth result. Bounded executor failure, timeout, and rejection
behavior remains the existing optional-engine behavior; it cannot affect required Rules execution while the
candidate is disconnected.
