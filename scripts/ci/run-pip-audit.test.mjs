import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { defaultRequirementsPath, parseLockedPackages, runAudit } from "./run-pip-audit.mjs";

const lockContents = [
  "alpha_pkg==1.2.3 \\",
  "    --hash=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "Beta-Pkg==4.5.6 \\",
  "    --hash=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  ""
].join("\n");

const cleanReport = [
  { name: "alpha-pkg", version: "1.2.3", vulns: [] },
  { name: "beta_pkg", version: "4.5.6", vulns: [] }
];

test("the application requirements file is an explicit exact-pin graph", () => {
  const packages = parseLockedPackages(fs.readFileSync(defaultRequirementsPath, "utf8"));
  assert.equal(packages.length, 7);
  assert.ok(packages.every(({ name, version }) => name.length > 0 && version.length > 0));
});

test("accepts clean JSON only when every locked package was audited", () => {
  withAuditFiles(({ requirementsPath, reportPath, diagnosticPath }) => {
    const result = runAudit({
      requirementsPath,
      reportPath,
      diagnosticPath,
      spawn: fakeScanner({ report: cleanReport, status: 0 })
    });
    assert.equal(result.status, 0);
    assert.equal(result.packageCount, 2);
    assert.equal(result.vulnerabilityCount, 0);
    assert.equal(fs.existsSync(diagnosticPath), false);
    assert.deepEqual(JSON.parse(fs.readFileSync(reportPath, "utf8")), cleanReport);
  });
});

test("preserves a valid vulnerable report and propagates the policy failure", () => {
  withAuditFiles(({ requirementsPath, reportPath, diagnosticPath }) => {
    const report = structuredClone(cleanReport);
    report[0].vulns.push({ id: "PYSEC-TEST-001", fix_versions: ["1.2.4"] });
    const result = runAudit({
      requirementsPath,
      reportPath,
      diagnosticPath,
      spawn: fakeScanner({ report, status: 1 })
    });
    assert.equal(result.status, 1);
    assert.equal(result.vulnerabilityCount, 1);
    assert.deepEqual(JSON.parse(fs.readFileSync(reportPath, "utf8")), report);
    assert.match(fs.readFileSync(diagnosticPath, "utf8"), /known vulnerability/);
  });
});

test("fails closed on malformed JSON while preserving the scanner output", () => {
  withAuditFiles(({ requirementsPath, reportPath, diagnosticPath }) => {
    const result = runAudit({
      requirementsPath,
      reportPath,
      diagnosticPath,
      spawn: fakeScanner({ rawReport: "{malformed", status: 0 })
    });
    assert.equal(result.status, 1);
    assert.equal(fs.readFileSync(reportPath, "utf8"), "{malformed");
    assert.match(fs.readFileSync(diagnosticPath, "utf8"), /valid JSON/);
  });
});

test("fails closed when the scanner exits without writing a report", () => {
  withAuditFiles(({ requirementsPath, reportPath, diagnosticPath }) => {
    const result = runAudit({
      requirementsPath,
      reportPath,
      diagnosticPath,
      spawn: fakeScanner({ status: 0 })
    });
    assert.equal(result.status, 1);
    assert.equal(fs.existsSync(reportPath), false);
    assert.match(fs.readFileSync(diagnosticPath, "utf8"), /no JSON report/);
  });
});

test("fails closed when pip-audit is unavailable and leaves an uploadable diagnostic", () => {
  withAuditFiles(({ requirementsPath, reportPath, diagnosticPath }) => {
    const result = runAudit({
      requirementsPath,
      reportPath,
      diagnosticPath,
      spawn: () => ({ error: Object.assign(new Error("missing"), { code: "ENOENT" }), status: null })
    });
    assert.equal(result.status, 127);
    assert.equal(fs.existsSync(reportPath), false);
    assert.match(fs.readFileSync(diagnosticPath, "utf8"), /ENOENT/);
  });
});

test("fails closed if pip-audit returns a report that omits locked packages", () => {
  withAuditFiles(({ requirementsPath, reportPath, diagnosticPath }) => {
    const result = runAudit({
      requirementsPath,
      reportPath,
      diagnosticPath,
      spawn: fakeScanner({ report: [cleanReport[0]], status: 0 })
    });
    assert.equal(result.status, 1);
    assert.match(fs.readFileSync(diagnosticPath, "utf8"), /lock pins 2/);
  });
});

function withAuditFiles(callback) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "pip-audit-fixture-"));
  try {
    const requirementsPath = path.join(temporary, "requirements.txt");
    const reportPath = path.join(temporary, "target", "pip-audit.json");
    const diagnosticPath = path.join(temporary, "target", "pip-audit-runner.txt");
    fs.writeFileSync(requirementsPath, lockContents, "utf8");
    callback({ requirementsPath, reportPath, diagnosticPath });
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}

function fakeScanner({ report, rawReport, status }) {
  return (_executable, args) => {
    if (args[0] === "--version") {
      return { status: 0, stdout: "pip-audit 2.10.1\n", stderr: "" };
    }
    const outputPath = args[args.indexOf("--output") + 1];
    if (rawReport !== undefined) fs.writeFileSync(outputPath, rawReport, "utf8");
    else if (report !== undefined) fs.writeFileSync(outputPath, JSON.stringify(report), "utf8");
    return { status, stdout: "", stderr: "" };
  };
}
