import assert from 'node:assert/strict';
import test from 'node:test';
import { runBoundedProcess } from './run-bounded-process.mjs';

test('classifies a missing executable without waiting for the process deadline', async () => {
  const result = await runBoundedProcess('fdp157-no-such-executable', [], { timeoutMs: 2_000 });
  assert.equal(result.status, 1);
  assert.equal(result.error?.code, 'ENOENT');
  assert.equal(result.timedOut, false);
});

test('terminates a child and reports a bounded timeout', async () => {
  const result = await runBoundedProcess(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
    timeoutMs: 100,
    killGraceMs: 100,
  });
  assert.equal(result.status, 124);
  assert.equal(result.timedOut, true);
  assert.equal(result.interrupted, false);
});

test('terminates descendants that keep inherited output pipes open after the direct child exits', {
  skip: process.platform === 'win32',
}, async () => {
  const childScript = 'const { spawn } = require("node:child_process"); spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)"], { stdio: "inherit" }); process.exit(0);';
  const result = await runBoundedProcess(process.execPath, ['-e', childScript], {
    timeoutMs: 100,
    killGraceMs: 100,
  });
  assert.equal(result.status, 124);
  assert.equal(result.timedOut, true);
});

test('terminates a child when the caller is interrupted', async () => {
  const controller = new AbortController();
  const execution = runBoundedProcess(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
    timeoutMs: 5_000,
    killGraceMs: 100,
    signal: controller.signal,
  });
  setTimeout(() => controller.abort(), 100).unref?.();
  const result = await execution;
  assert.equal(result.status, 130);
  assert.equal(result.interrupted, true);
  assert.equal(result.timedOut, false);
});

test('keeps a bounded output tail and marks truncated diagnostics', async () => {
  const result = await runBoundedProcess(process.execPath, ['-e', 'process.stdout.write("0123456789")'], {
    timeoutMs: 2_000,
    maxOutputBytes: 4,
  });
  assert.equal(result.status, 0);
  assert.equal(result.stdout, '6789');
  assert.equal(result.outputTruncated, true);
});
