# Audit chain index bootstrap and migration

`AuditChainIndexInitializer` is the only application owner that creates the required unique audit indexes. Spring Data may create the remaining query indexes, but `AuditEventDocument` does not declare a second `request_id` unique index.

## Read-only preflight

Run this against the target database before a deployment or an index migration:

```powershell
mongosh "$env:MONGODB_URI" --quiet --file scripts/audit-chain-index-preflight.js
```

The script only reads `listIndexes()` metadata and duplicate groups. `PRESENT` means the canonical unique index is installed; `MISSING` with `duplicatesDetected: false` is safe for normal application bootstrap to create. `LEGACY_SPARSE`, `INCOMPATIBLE`, or `DUPLICATE_DATA` requires operator investigation. Startup reports the same classifications and does not drop indexes or alter audit documents.

The canonical contracts are:

| Collection | Ordered key | Unique | Partial filter |
|---|---|---:|---|
| `audit_events` | `{ partition_key: 1, chain_position: 1 }` | yes | both keys exist |
| `audit_chain_anchors` | `{ partition_key: 1, chain_position: 1 }` | yes | both keys exist |
| `audit_events` | `{ request_id: 1 }` | yes | `request_id` is a string |

## Operator-approved migration

Do not use application startup to convert a legacy or incompatible index. For a reported conflict:

1. Schedule a maintenance window and stop every application instance and worker that can append audit events. Take and verify a MongoDB backup. Save the output of `db.audit_events.getIndexes()` and `db.audit_chain_anchors.getIndexes()` with the change record.
2. Run the read-only preflight. For a legacy sparse `request_id` index, also confirm there are no duplicate values among all present `request_id` fields, including null or non-string values:

   ```javascript
   db.audit_events.aggregate([
     { $match: { request_id: { $exists: true } } },
     { $unwind: "$request_id" },
     { $group: { _id: "$request_id", documents: { $addToSet: "$_id" } } },
     { $match: { $expr: { $gt: [{ $size: "$documents" }, 1] } } },
     { $limit: 1 }
   ]).toArray()
   ```

   The result must be empty before migration. Investigate `DUPLICATE_DATA` or any unexpected BSON types without deleting or rewriting audit evidence.
3. Obtain the deployment's normal database-change approval. With writers still stopped, replace only the reported index. For the legacy request index:

   ```javascript
   db.audit_events.dropIndex("audit_request_id_idx")
   db.audit_events.createIndex(
     { request_id: 1 },
     {
       name: "audit_request_id_uidx",
       unique: true,
       partialFilterExpression: { request_id: { $type: "string" } }
     }
   )
   ```

   For a chain-position index, use the collection named by preflight and the exact ordered key and filter from the table above. Do not change, compact, or remove event or anchor documents.
4. Re-run the preflight and verify every required index is `PRESENT`. Start one application instance, confirm startup/readiness and the emitted `listIndexes()` metadata, then resume the rest of the fleet.

If rollback is needed, stop all writers again. Restore the saved index only after the duplicate check for its original semantics is empty. If duplicates appeared under the new partial contract, do not delete evidence to force rollback; keep writers stopped and follow the approved incident procedure. The backup and saved metadata provide the rollback reference.
