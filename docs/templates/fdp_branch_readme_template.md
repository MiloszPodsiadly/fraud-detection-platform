# FDP-XX Branch README

## Branch Summary

State the branch number, short name, and one-sentence outcome. Keep it branch-specific.

## Why This Branch Exists

Name the gap this branch closes. Avoid copying previous branch history.

## In Scope

- List only the files, jobs, tests, or docs intentionally changed.
- Link durable CI proof to `../ci_evidence_map.md`.

## Out Of Scope

- Name explicit non-goals.
- Include backend, auth, mutation, product workflow, and deployment non-goals when relevant.

## Architecture Impact

Describe whether the branch changes architecture. If it does not, say which existing seam it uses.

## ACID / Mutation Impact

State whether transaction, idempotency, outbox, Kafka, or finality behavior changed.

## Security/Auth Impact

State whether tokens, identity, authorization, CSRF, BFF, or route ownership changed. Backend enforcement remains authoritative unless a backend change explicitly says otherwise.

## CI Evidence

- Link `../ci_evidence_map.md`.
- Name the exact merge gate and test/script evidence.
- Do not copy an entire CI proof matrix into this README.

## NO MERCY Merge Gate

- Link `../reviewer_checklist.md`.
- List the concrete gate commands or CI jobs that must be green.

## Remaining Risks

Name known limitations. Do not present future work as current behavior.

## Reviewer Summary

Give reviewers the shortest useful summary: changed areas, non-goals, and proof.

## Template Rules

- Do not copy/paste entire prior branch READMEs.
- Do not claim production posture without evidence and limitations.
- Keep branch-specific content specific.
- Move long-lived policy to `../fdp/branch_index.md`, `../ci_evidence_map.md`, or another central doc.
