import assert from "node:assert/strict";
import test from "node:test";
import {
  isRetryableMavenFailure,
  classifyMavenFailure,
  redactMavenDiagnostics,
  retryAfterMilliseconds,
  runMavenResolution
} from "./resolve-maven-for-trivy.mjs";

test("parses numeric and HTTP-date Retry-After values", () => {
  assert.equal(retryAfterMilliseconds("HTTP 429 Too Many Requests\nRetry-After: 1800"), 1_800_000);
  assert.equal(retryAfterMilliseconds("Retry-After: Wed, 25 Nov 2026 10:00:00 GMT", Date.parse("2026-11-25T09:59:00Z")),
    60_000);
  assert.equal(retryAfterMilliseconds("Retry-After: invalid"), undefined);
  assert.equal(retryAfterMilliseconds("Retry-After: 1800."), undefined);
  assert.equal(retryAfterMilliseconds("Retry-After: Tue, 25 Nov 2026 10:00:00 GMT"), undefined);
});

test("classifies timeout, interruption, network, and deterministic policy failures", () => {
  assert.equal(classifyMavenFailure({ timedOut: true }, ""), "timeout");
  assert.equal(classifyMavenFailure({ interrupted: true }, ""), "interrupted");
  assert.equal(classifyMavenFailure({ status: 1 }, "Connection reset"), "network");
  assert.equal(classifyMavenFailure({ status: 1 }, "Could not find artifact missing:artifact:1"), "resolution-or-policy");
});

test("redacts repository credentials and sensitive Maven properties from diagnostics", () => {
  assert.equal(redactMavenDiagnostics("https://user:password@repo.invalid/x token=abc123"),
    "https://[REDACTED]@repo.invalid/x token=[REDACTED]");
});

test("identifies only transient Maven transport and repository failures", () => {
  assert.equal(isRetryableMavenFailure("remote Maven repository returned 429 Too Many Requests"), true);
  assert.equal(isRetryableMavenFailure("HTTP/1.1 503 Service Unavailable"), true);
  assert.equal(isRetryableMavenFailure("HTTP/1.1 500 Internal Server Error"), true);
  assert.equal(isRetryableMavenFailure("Connection reset by peer"), true);
  assert.equal(isRetryableMavenFailure("Could not find artifact example:dependency:jar:1.0"), false);
});

test("honors a bounded Retry-After delay before a successful retry", async () => {
  let calls = 0;
  const waits = [];
  const result = await runMavenResolution({
    execute: async () => {
      calls += 1;
      return calls === 1
        ? { status: 1, stderr: "HTTP 429 Too Many Requests\nRetry-After: 2\n" }
        : { status: 0, stdout: "resolved" };
    },
    sleep: async (milliseconds) => waits.push(milliseconds),
    onOutput: () => {}
  });
  assert.deepEqual(waits, [2000]);
  assert.equal(calls, 2);
  assert.equal(result.status, 0);
  assert.equal(result.totalWaitMs, 2000);
});

test("does not retry before a Retry-After that exceeds the retry budget", async () => {
  let calls = 0;
  let slept = false;
  const result = await runMavenResolution({
    execute: async () => {
      calls += 1;
      return { status: 1, stderr: "remote Maven repository returned 429 Too Many Requests\nRetry-After: 1800" };
    },
    sleep: async () => { slept = true; },
    maxTotalRetryWaitMs: 5000,
    onOutput: () => {}
  });
  assert.equal(calls, 1);
  assert.equal(slept, false);
  assert.equal(result.status, 1);
  assert.match(result.message, /beyond the remaining 5s bounded budget/);
});

test("applies the total deadline to retry delays", async () => {
  let elapsed = 0;
  let calls = 0;
  const result = await runMavenResolution({
    execute: async () => {
      calls += 1;
      elapsed = 90;
      return { status: 1, stderr: "HTTP 429 Too Many Requests\nRetry-After: 1" };
    },
    monotonicNow: () => elapsed,
    maxTotalDurationMs: 100,
    maxTotalRetryWaitMs: 10_000,
    sleep: async () => assert.fail("the retry delay must fit inside the remaining total deadline"),
    onOutput: () => {}
  });
  assert.equal(calls, 1);
  assert.equal(result.failureKind, "timeout");
  assert.match(result.message, /remaining 0s bounded budget/);
});

test("uses bounded exponential backoff and exhausts without hiding the failure", async () => {
  let calls = 0;
  const waits = [];
  const result = await runMavenResolution({
    execute: async () => {
      calls += 1;
      return { status: 1, stderr: "HTTP 503 Service Unavailable" };
    },
    sleep: async (milliseconds) => waits.push(milliseconds),
    onOutput: () => {}
  });
  assert.equal(calls, 3);
  assert.deepEqual(waits, [1000, 2000]);
  assert.equal(result.status, 1);
});

test("does not retry a deterministic Maven resolution failure", async () => {
  let calls = 0;
  const result = await runMavenResolution({
    execute: async () => {
      calls += 1;
      return { status: 1, stderr: "Could not find artifact example:missing:jar:1.0" };
    },
    sleep: async () => assert.fail("deterministic failures must not be retried"),
    onOutput: () => {}
  });
  assert.equal(calls, 1);
  assert.equal(result.status, 1);
});

test("does not retry an interrupted Maven process", async () => {
  let calls = 0;
  const result = await runMavenResolution({
    execute: async () => {
      calls += 1;
      return { status: 130, interrupted: true };
    },
    sleep: async () => assert.fail("an interrupted process must not be retried"),
    onOutput: () => {},
  });
  assert.equal(calls, 1);
  assert.equal(result.failureKind, "interrupted");
  assert.equal(result.status, 130);
});
