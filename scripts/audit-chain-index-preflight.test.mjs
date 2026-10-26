import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('./audit-chain-index-preflight.js', import.meta.url), 'utf8');

function index(key, options = {}) {
  return { name: options.name ?? 'test-index', key, ...options };
}

function runPreflight(indexesByCollection, duplicateCollections = new Set()) {
  const output = [];
  const exitCodes = [];
  const database = {
    getName: () => 'test-audit-db',
    getCollection: (name) => ({
      getIndexes: () => indexesByCollection[name] ?? [{ name: '_id_', key: { _id: 1 } }],
      aggregate: (pipeline) => {
        const purpose = Object.hasOwn(pipeline[0].$match, 'request_id') ? 'request-id' : 'chain-position';
        return { hasNext: () => duplicateCollections.has(`${name}:${purpose}`) };
      },
    }),
  };
  const exitSignal = {};
  try {
    vm.runInNewContext(source, {
      db: database,
      printjson: (value) => output.push(JSON.parse(JSON.stringify(value))),
      quit: (code) => {
        exitCodes.push(code);
        throw exitSignal;
      },
    });
  } catch (failure) {
    if (failure !== exitSignal) throw failure;
  }
  return { output: output[0], exitCodes };
}

test('reports canonical indexes as present without changing the database', () => {
  const result = runPreflight({
    audit_events: [
      { name: '_id_', key: { _id: 1 } },
      index({ partition_key: 1, chain_position: 1 }, {
        unique: true,
        partialFilterExpression: { partition_key: { $exists: true }, chain_position: { $exists: true } },
      }),
      index({ request_id: 1 }, {
        unique: true,
        partialFilterExpression: { request_id: { $type: 'string' } },
      }),
    ],
    audit_chain_anchors: [
      { name: '_id_', key: { _id: 1 } },
      index({ partition_key: 1, chain_position: 1 }, {
        unique: true,
        partialFilterExpression: { partition_key: { $exists: true }, chain_position: { $exists: true } },
      }),
    ],
  });

  assert.equal(result.output.readOnly, true);
  assert.deepEqual(result.output.indexes.map((entry) => entry.state), ['PRESENT', 'PRESENT', 'PRESENT']);
  assert.deepEqual(result.exitCodes, []);
});

test('reports a legacy sparse request index and returns a nonzero status', () => {
  const result = runPreflight({
    audit_events: [
      { name: '_id_', key: { _id: 1 } },
      index({ request_id: 1 }, { name: 'audit_request_id_idx', unique: true, sparse: true }),
    ],
  });

  assert.equal(result.output.indexes[2].state, 'LEGACY_SPARSE');
  assert.equal(result.exitCodes.at(-1), 2);
});

test('reports a reversed compound key as incompatible', () => {
  const result = runPreflight({
    audit_events: [
      { name: '_id_', key: { _id: 1 } },
      index({ chain_position: 1, partition_key: 1 }, {
        unique: true,
        partialFilterExpression: { partition_key: { $exists: true }, chain_position: { $exists: true } },
      }),
    ],
  });

  assert.equal(result.output.indexes[0].state, 'INCOMPATIBLE');
  assert.equal(result.exitCodes.at(-1), 2);
});

test('reports a request index with non-default collation as incompatible', () => {
  const result = runPreflight({
    audit_events: [
      { name: '_id_', key: { _id: 1 } },
      index({ request_id: 1 }, {
        unique: true,
        collation: { locale: 'en', strength: 1 },
        partialFilterExpression: { request_id: { $type: 'string' } },
      }),
    ],
  });

  assert.equal(result.output.indexes[2].state, 'INCOMPATIBLE');
  assert.equal(result.exitCodes.at(-1), 2);
});

test('reports clean missing indexes as safe for application bootstrap', () => {
  const result = runPreflight({});

  assert.deepEqual(result.output.indexes.map((entry) => entry.state), ['MISSING', 'MISSING', 'MISSING']);
  assert.deepEqual(result.exitCodes, []);
});

test('reports duplicate request IDs and returns a nonzero status', () => {
  const result = runPreflight({}, new Set(['audit_events:request-id']));

  assert.equal(result.output.indexes[0].state, 'MISSING');
  assert.equal(result.output.indexes[2].state, 'DUPLICATE_DATA');
  assert.equal(result.output.indexes[2].duplicatesDetected, true);
  assert.equal(result.exitCodes.at(-1), 2);
});
