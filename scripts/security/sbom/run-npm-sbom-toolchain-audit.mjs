#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const [outputPathArgument, npmCliPathArgument] = process.argv.slice(2);
if (!outputPathArgument || process.argv.length > 4) {
  throw new Error('Usage: node run-npm-sbom-toolchain-audit.mjs <output-json-path> [npm-cli-path]');
}

const outputPath = resolve(outputPathArgument);
const args = ['audit', '--json', '--audit-level=low'];
const npmCliPath = npmCliPathArgument ?? process.env.NPM_CLI_PATH;
const result = npmCliPath
  ? spawnSync(process.execPath, [resolve(npmCliPath), ...args], { cwd: process.cwd() })
  : spawnSync('npm', args, { cwd: process.cwd(), shell: process.platform === 'win32' });

if (result.stdout?.length) {
  mkdirSync(dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, result.stdout);
}
if (result.stderr?.length) process.stderr.write(result.stderr);
if (result.error) throw new Error(`npm audit could not run: ${result.error.message}`);
if (result.status !== 0) process.exit(result.status ?? 1);

let report;
try {
  report = JSON.parse(result.stdout.toString('utf8').replace(/^\uFEFF/, ''));
} catch {
  throw new Error('npm audit returned malformed JSON evidence.');
}
if (!Number.isInteger(report.metadata?.vulnerabilities?.total)) {
  throw new Error('npm audit report is missing its complete vulnerability summary.');
}
if (report.metadata.vulnerabilities.total !== 0) {
  throw new Error(`npm audit found ${report.metadata.vulnerabilities.total} vulnerabilities in the SBOM toolchain.`);
}
process.stdout.write(`Audited ${report.metadata.dependencies?.total ?? 'unknown'} locked npm SBOM toolchain dependencies.\n`);
