# FDP-45 Work Queue Cursor Secret Rotation

## Scope

This runbook covers rotation of `FRAUD_CASE_WORK_QUEUE_CURSOR_SIGNING_SECRET` for the FDP-45 fraud-case work queue cursor.

## Expected Impact

Existing work queue cursors may become invalid after the signing secret changes. The API returns `INVALID_CURSOR` for those cursors, and clients should restart traversal without a cursor while preserving the intended filters and sort.

This is not data loss, not a lifecycle mutation failure, and not an audit failure. It only invalidates traversal tokens signed with the previous secret.

## Operator Guidance

Deploy the new secret through the configured secret manager or environment variable path. Production-like `prod` and `bank` profiles must not use local default cursor signing secrets.

Expect a temporary increase in the low-cardinality `invalid_cursor` metric after deployment. Monitor the work queue invalid cursor rate and verify normal cursor traversal by requesting the first work queue slice, following `nextCursor`, and confirming the next slice succeeds.

Current FDP-45 supports one active cursor signing secret. Future hardening may add a non-secret key id and a bounded multi-key verification window, but that is not implemented in this branch.

## Client Guidance

Treat the cursor as opaque. Do not parse cursor fields, do not persist cursors long-term, and do not retry the same invalid cursor indefinitely.

On `INVALID_CURSOR`, restart from the first slice with the current filters and sort. Cursor invalidation does not require changing fraud-case state or retrying a mutation.

## Security

The cursor is signed for integrity, not encrypted. The encoded payload may contain traversal fields such as the last sort value and last id, so the cursor must not be treated as confidential storage.

Do not log cursor values. Do not use cursor values, query hashes, last values, or last ids as metric labels. Do not copy cursor internals into read-access audit payloads.
