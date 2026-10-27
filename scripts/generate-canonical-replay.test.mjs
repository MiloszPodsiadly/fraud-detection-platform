import { execFileSync } from "node:child_process";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repositoryRoot = resolve(fileURLToPath(new URL("..", import.meta.url)));

test("canonical replay generator produces identical test output for a fixed seed and reference instant", () => {
    const temporaryDirectory = mkdtempSync(join(tmpdir(), "fdp149-canonical-replay-"));
    try {
      const dimensionsPath = join(temporaryDirectory, "dimensions.json");
      const firstOutputPath = join(temporaryDirectory, "first.jsonl");
      const secondOutputPath = join(temporaryDirectory, "second.jsonl");
      writeFileSync(dimensionsPath, JSON.stringify(dimensionsFixture()), "utf8");

      generate(firstOutputPath, dimensionsPath);
      generate(secondOutputPath, dimensionsPath);

      const firstBytes = readFileSync(firstOutputPath);
      const secondBytes = readFileSync(secondOutputPath);
      assert.deepEqual(firstBytes, secondBytes);

      const events = firstBytes.toString("utf8").trim().split("\n").map((line) => JSON.parse(line));
      assert.equal(new Set(events.map((event) => event.eventId)).size, events.length);
      assert.equal(new Set(events.map((event) => event.correlationId)).size, events.length);
      assert.equal(new Set(events.map((event) => event.transactionId)).size, events.length);
      assert.equal(new Set(events.map((event) => event.traceId)).size, events.length);
      assert.ok(events.every((event) => event.sourceSystem === "BASH_JSONL_GENERATOR"));
      assert.ok(events.every((event) => event.deviceInfo.attributes.synthetic === true));
      assert.ok(events.every((event) => event.attributes.deviceEvidenceProvenance === "SYNTHETIC_UNTRUSTED"));
      assert.ok(events.every((event) => event.deviceInfo.attributes.deviceEvidenceProvenance === "SYNTHETIC_UNTRUSTED"));

      const fingerprintByDevice = new Map();
      for (const event of events) {
        const { deviceId, fingerprint } = event.deviceInfo;
        assert.equal(fingerprint, `syn-fp-${deviceId}`);
        assert.equal(fingerprintByDevice.get(deviceId) ?? fingerprint, fingerprint);
        fingerprintByDevice.set(deviceId, fingerprint);
      }
      assert.equal(new Set(fingerprintByDevice.values()).size, fingerprintByDevice.size);
    } finally {
      rmSync(temporaryDirectory, { recursive: true, force: true });
    }
});

test("canonical replay keeps A→A bytes while A→B→A separates deterministic identities", () => {
  const temporaryDirectory = mkdtempSync(join(tmpdir(), "fdp149-canonical-replay-identity-"));
  try {
    const dimensionsPath = join(temporaryDirectory, "dimensions.json");
    const firstA = join(temporaryDirectory, "first-a.jsonl");
    const changedB = join(temporaryDirectory, "changed-b.jsonl");
    const repeatedA = join(temporaryDirectory, "repeated-a.jsonl");
    writeFileSync(dimensionsPath, JSON.stringify(dimensionsFixture()), "utf8");

    generate(firstA, dimensionsPath, true, { seed: "7341" });
    generate(changedB, dimensionsPath, true, { seed: "7342" });
    generate(repeatedA, dimensionsPath, true, { seed: "7341" });

    assert.deepEqual(readFileSync(firstA), readFileSync(repeatedA));
    const aEvents = readFileSync(firstA, "utf8").trim().split("\n").map((line) => JSON.parse(line));
    const bEvents = readFileSync(changedB, "utf8").trim().split("\n").map((line) => JSON.parse(line));
    const aIdentities = new Set(aEvents.flatMap((event) => [event.eventId, event.correlationId, event.transactionId, event.traceId]));
    assert.ok(bEvents.flatMap((event) => [event.eventId, event.correlationId, event.transactionId, event.traceId])
      .every((identity) => !aIdentities.has(identity)));
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test("canonical replay default mode creates fresh transaction and trace identities per run", () => {
  const temporaryDirectory = mkdtempSync(join(tmpdir(), "fdp149-canonical-replay-default-"));
  try {
    const dimensionsPath = join(temporaryDirectory, "dimensions.json");
    const firstOutputPath = join(temporaryDirectory, "first.jsonl");
    const secondOutputPath = join(temporaryDirectory, "second.jsonl");
    writeFileSync(dimensionsPath, JSON.stringify(dimensionsFixture()), "utf8");

    generate(firstOutputPath, dimensionsPath, false);
    generate(secondOutputPath, dimensionsPath, false);

    const firstEvents = readFileSync(firstOutputPath, "utf8").trim().split("\n").map((line) => JSON.parse(line));
    const secondEvents = readFileSync(secondOutputPath, "utf8").trim().split("\n").map((line) => JSON.parse(line));
    const firstTransactionIds = new Set(firstEvents.map((event) => event.transactionId));
    const firstTraceIds = new Set(firstEvents.map((event) => event.traceId));

    assert.ok(secondEvents.every((event) => !firstTransactionIds.has(event.transactionId)));
    assert.ok(secondEvents.every((event) => !firstTraceIds.has(event.traceId)));
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test("synthetic replay wrapper forwards deterministic test mode", () => {
  const temporaryDirectory = mkdtempSync(join(tmpdir(), "fdp149-synthetic-replay-"));
  try {
    const firstOutputPath = join(temporaryDirectory, "first.jsonl");
    const secondOutputPath = join(temporaryDirectory, "second.jsonl");

    generateViaWrapper(firstOutputPath, temporaryDirectory);
    generateViaWrapper(secondOutputPath, temporaryDirectory);

    assert.deepEqual(readFileSync(firstOutputPath), readFileSync(secondOutputPath));
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

function generate(outputPath, dimensionsPath, deterministicTestMode = true, { seed = "7341" } = {}) {
  const python = process.env.PYTHON || (process.platform === "win32" ? "python" : "python3");
  const bash = process.env.BASH || (process.platform === "win32"
    ? "C:\\Program Files\\Git\\bin\\bash.exe"
    : "bash");
  execFileSync(bash, [
    "scripts/generate-canonical-replay.sh",
    "--count", "40",
    "--seed", seed,
    "--dimensions", dimensionsPath,
    "--output", outputPath,
    "--reference-instant", "2026-01-01T08:00:00Z",
    "--normal-percentage", "100",
    "--rapid-transfer-seed-percentage", "0",
    "--rapid-transfer-percentage", "0",
    "--new-device-percentage", "0",
    "--high-proxy-percentage", "0",
    "--country-mismatch-percentage", "0",
    "--account-takeover-percentage", "0",
    ...(deterministicTestMode ? ["--deterministic-test-mode"] : [])
  ], {
    cwd: repositoryRoot,
    env: { ...process.env, PYTHON: python },
    stdio: "ignore"
  });
}

function generateViaWrapper(outputPath, workingDirectory) {
  const python = process.env.PYTHON || (process.platform === "win32" ? "python" : "python3");
  const bash = process.env.BASH || (process.platform === "win32"
    ? "C:\\Program Files\\Git\\bin\\bash.exe"
    : "bash");
  execFileSync(bash, [
    resolve(repositoryRoot, "scripts/generate-synthetic-replay.sh"),
    "--count", "40",
    "--seed", "7341",
    "--output", outputPath,
    "--deterministic-test-mode"
  ], {
    cwd: workingDirectory,
    env: { ...process.env, PYTHON: python },
    stdio: "ignore"
  });
}

function dimensionsFixture() {
  return {
    customers: [{
      customerId: "syn-customer-1",
      accountId: "syn-account-1",
      paymentInstrumentId: "syn-card-1",
      homeLocation: {
        region: "Mazowieckie",
        city: "Warsaw",
        postalCode: "00-001",
        latitude: 52.2297,
        longitude: 21.0122
      },
      knownDeviceIds: ["syn-device-1", "syn-device-2"],
      homeCountryCode: "PL",
      segment: "STANDARD",
      emailVerified: true,
      phoneVerified: true,
      accountAgeDays: 365,
      preferredCurrency: "PLN",
      timezone: "Europe/Warsaw",
      emailDomain: "customer.example"
    }],
    merchants: [{
      merchantId: "syn-merchant-1",
      merchantName: "Synthetic Grocery",
      merchantCategoryCode: "5411",
      merchantCategory: "Groceries",
      defaultChannel: "POS",
      riskTier: "LOW"
    }]
  };
}
