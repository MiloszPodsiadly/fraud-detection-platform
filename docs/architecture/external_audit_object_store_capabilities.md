# External audit object-store deployment capabilities

The repository contains a provider-neutral `ObjectStoreAuditAnchorClient` contract and a fail-closed sink algorithm. It does not contain a production object-store adapter. Environment variables for bucket, region, endpoint, and credentials configure an adapter; they do not create one.

| Provider | Adapter | Storage CAS guarantee | Consistency model | Evidence | Supported profiles | Remaining limitation |
| --- | --- | --- | --- | --- | --- | --- |
| Disabled | `DisabledExternalAuditAnchorSink` | None | None | Configuration tests | Local and non-required deployments only | No external witness or publication guarantee |
| Local filesystem | `LocalFileExternalAuditAnchorSink` | None | Host-local append behavior only | Unit tests | `local`, `dev`, `test`, `docker-local`; forbidden in prod-like profiles | Not independent, WORM, retained, or provider-CAS storage |
| Generic object store | No production adapter | Not proven | Unknown | Sink algorithm and in-memory concurrency tests only | None | A reviewed provider adapter must prove atomic create-if-absent, version-token CAS, read-after-write behavior, retention, immutability, stable references, and independent-instance concurrency |
| Test fixture | `InMemoryObjectStoreAuditAnchorClient` in test sources | Process-local simulated CAS | Process-local | Object-store sink tests | Tests only | Not production evidence and never wired by application configuration |

Selecting `sink=object-store` fails startup when no client bean exists. A supplied client also fails startup unless it declares linearizable create-and-CAS support; strict publication additionally requires verified witness independence, versioning, retention, timestamp trust, read-after-write, overwrite protection, delete protection, and stable references. Prod-like deployments therefore remain explicitly unsupported until a real provider adapter and provider-level conformance suite are added.

Timeout after a conditional write remains an uncertain outcome. The sink verifies readable HEAD state before reporting success, while an unverified timeout is reported as failed and cannot satisfy strict trust posture.
