# Model Card publication recovery

Use this procedure only for an interrupted offline Model Card publication. Recovery is operator-controlled and fails closed whenever lock ownership is uncertain.

1. Stop or suspend every scheduled and manually started Model Card publisher for the affected artifact root. Confirm termination through the workload manager and host process inventory. The PID recorded in the lock is supporting evidence only; PID absence or reuse does not prove ownership ended.
2. Record the incident time, deployment or job identity, artifact version, host, operator, lock contents and file metadata, relevant publisher logs, and hashes of any files inspected. Do not follow symlinks while inspecting the artifact root.
3. Inspect the final version directory first. If it exists, do not delete or replace it. Validate it with the canonical Python Model Card reader. A valid directory is published evidence; an invalid or ambiguous directory requires escalation rather than an attempted republish of the same version.
4. Read the exact `.<version>.publish.lock` as data and correlate its `ownerToken`, host, PID, and creation time with workload-manager and publisher logs. Age or PID alone is never sufficient. If a publisher may still own the lock, stop and escalate.
5. Inspect only staging directories named `.<version>.staging-*`. Never treat them as published evidence and never move them into the final destination. Remove or quarantine a staging directory only after proving the owning publisher has terminated, the final destination is absent, and the directory belongs to the incident being recovered. Leave unrelated staging directories untouched.
6. After ownership is conclusively cleared and the destination is confirmed absent, remove only the exact stale lock. Rechecking or repeating removal of an already absent stale lock is harmless; encountering a new lock means a publisher started and recovery must stop.
7. Start one fresh publisher for the same deterministic input and version. It creates and validates a new staging directory and performs the normal atomic no-replace publication. Never reuse an interrupted staging directory or bypass the writer.
8. Validate the resulting directory with the canonical reader, retain the incident record, and resume scheduling only after the result is unambiguous.

Hard process termination between filesystem operations cannot be deterministically reproduced in-process. Automated tests therefore simulate the durable post-crash state: an orphaned lock, an independently owned staging directory, and no destination. The tests prove that another publisher refuses the stale lock, recovery never removes foreign staging, manual removal permits a fresh publication, and a published version remains immutable.
