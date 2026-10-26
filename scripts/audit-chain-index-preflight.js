// Read-only MongoDB index inspection. Run with:
// mongosh "$MONGODB_URI" --quiet --file scripts/audit-chain-index-preflight.js

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

function sameDocument(left, right) {
  return JSON.stringify(canonical(left)) === JSON.stringify(canonical(right));
}

function sameOrderedKey(index, expected) {
  const entries = Object.entries(index.key || {});
  return JSON.stringify(entries) === JSON.stringify(expected);
}

function sameKeyFields(index, expected) {
  const actualFields = Object.keys(index.key || {}).sort();
  const expectedFields = expected.map(([field]) => field).sort();
  return JSON.stringify(actualFields) === JSON.stringify(expectedFields);
}

function duplicateExists(collection, pipeline) {
  return collection.aggregate(pipeline).hasNext();
}

function inspect(collectionName, purpose, expectedKey, expectedFilter) {
  const collection = db.getCollection(collectionName);
  const matches = collection.getIndexes().filter((index) => sameKeyFields(index, expectedKey));
  const exact = matches.find((index) => sameOrderedKey(index, expectedKey) && index.unique === true
    && !index.collation && sameDocument(index.partialFilterExpression, expectedFilter));
  const incompatible = matches.find((index) => index !== exact
    && (index.unique === true || !exact || !sameOrderedKey(index, expectedKey)));
  let duplicatesDetected = false;

  if (!exact || incompatible) {
    const position = purpose === "chain-position";
    const duplicatePipeline = position
      ? [
        { $match: { partition_key: { $exists: true }, chain_position: { $exists: true } } },
        { $unwind: "$partition_key" },
        { $unwind: "$chain_position" },
        { $group: { _id: { partition: "$partition_key", position: "$chain_position" }, documents: { $addToSet: "$_id" } } },
        { $match: { $expr: { $gt: [{ $size: "$documents" }, 1] } } },
        { $limit: 1 },
      ]
      : [
        { $match: { request_id: { $type: "string" } } },
        { $unwind: "$request_id" },
        { $match: { request_id: { $type: "string" } } },
        { $group: { _id: "$request_id", documents: { $addToSet: "$_id" } } },
        { $match: { $expr: { $gt: [{ $size: "$documents" }, 1] } } },
        { $limit: 1 },
      ];
    duplicatesDetected = duplicateExists(collection, duplicatePipeline);
  }

  let state;
  let detail;
  if (incompatible) {
    const legacySparse = incompatible.unique === true && incompatible.sparse === true
      && !incompatible.partialFilterExpression;
    state = legacySparse ? "LEGACY_SPARSE" : "INCOMPATIBLE";
    detail = legacySparse
      ? "Requires an operator-approved maintenance migration; no index was changed."
      : "Key matches but unique, collation, or partial-filter options do not match the canonical contract.";
  } else if (exact) {
    state = "PRESENT";
    detail = "Canonical unique index is installed.";
  } else if (duplicatesDetected) {
    state = "DUPLICATE_DATA";
    detail = "Resolve the durable-data conflict under an audited operator procedure; do not delete evidence.";
  } else {
    state = "MISSING";
    detail = "No conflict detected; application bootstrap may create this index.";
  }

  return {
    collection: collectionName,
    purpose,
    state,
    expectedKey,
    expectedPartialFilter: expectedFilter,
    observedIndexes: matches.map((index) => ({
      name: index.name,
      unique: index.unique === true,
      sparse: index.sparse === true,
      partialFilterExpression: index.partialFilterExpression || null,
    })),
    duplicatesDetected,
    detail,
  };
}

const checks = [
  inspect("audit_events", "chain-position", [["partition_key", 1], ["chain_position", 1]], {
    partition_key: { $exists: true },
    chain_position: { $exists: true },
  }),
  inspect("audit_chain_anchors", "chain-position", [["partition_key", 1], ["chain_position", 1]], {
    partition_key: { $exists: true },
    chain_position: { $exists: true },
  }),
  inspect("audit_events", "request-id", [["request_id", 1]], {
    request_id: { $type: "string" },
  }),
];

printjson({ readOnly: true, database: db.getName(), indexes: checks });
if (checks.some((check) => !["PRESENT", "MISSING"].includes(check.state)
    || check.duplicatesDetected)) {
  quit(2);
}
