import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, '../../..');
const centralRunner = path.resolve(scriptDirectory, '../run-security-gate.mjs');
const toolchainDirectory = path.join(scriptDirectory, 'npm-sbom-tools');

test('central npm audit phase resolves the explicit toolchain when invoked from a non-root directory with spaces', () => {
  const invocationDirectory = mkdtempSync(path.join(os.tmpdir(), 'fdp npm audit cwd '));
  const stubDirectory = mkdtempSync(path.join(os.tmpdir(), 'fdp npm cli stub '));
  const stubCliPath = path.join(stubDirectory, 'npm cli.mjs');
  const observedCwdPath = path.join(stubDirectory, 'observed cwd.txt');
  const outputPath = path.join(repositoryRoot, 'target', 'sbom', `npm audit path test ${process.pid}.json`);
  mkdirSync(path.dirname(outputPath), { recursive: true });
  writeFileSync(stubCliPath, [
    "import { readFileSync, writeFileSync } from 'node:fs';",
    "if (process.argv.includes('--version')) { process.stdout.write('12.2.3\\n'); } else {",
    "  writeFileSync(process.env.NPM_AUDIT_CWD_CAPTURE, process.cwd());",
    "  const lock = JSON.parse(readFileSync('package-lock.json', 'utf8'));",
    "  const total = Object.entries(lock.packages).filter(([name, item]) => name !== '' && item.link !== true).length;",
    "  process.stdout.write(JSON.stringify({ auditReportVersion: 2, vulnerabilities: {}, metadata: { vulnerabilities: { info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0 }, dependencies: { total } } }));",
    "}",
  ].join('\n'));

  try {
    const result = spawnSync(process.execPath, [
      centralRunner,
      'npm-sbom-audit',
      outputPath,
      toolchainDirectory,
      stubCliPath,
    ], {
      cwd: invocationDirectory,
      encoding: 'utf8',
      env: { ...process.env, NPM_AUDIT_CWD_CAPTURE: observedCwdPath },
    });
    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.equal(readFileSync(observedCwdPath, 'utf8'), toolchainDirectory);
    assert.equal(JSON.parse(readFileSync(outputPath, 'utf8')).auditReportVersion, 2);
  } finally {
    rmSync(outputPath, { force: true });
    rmSync(invocationDirectory, { recursive: true, force: true });
    rmSync(stubDirectory, { recursive: true, force: true });
  }
});
