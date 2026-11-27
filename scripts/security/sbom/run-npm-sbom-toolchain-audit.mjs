#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SECURITY_POLICY } from '../config/policy.mjs';
import { NPM_SBOM_TOOLCHAIN_PACKAGE_NAME, resolveNpmSbomAuditPaths } from './npm-sbom-audit-paths.mjs';
import { verifyNpmSbomToolchainEvidence } from './verify-npm-sbom-toolchain.mjs';

const [outputPathArgument, packageDirectoryArgument, npmCliPathArgument] = process.argv.slice(2);
if (!outputPathArgument || !packageDirectoryArgument || process.argv.length > 5) {
  throw new Error('Usage: node run-npm-sbom-toolchain-audit.mjs <output-json-path> <verified-package-dir> [npm-cli-path]');
}

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const paths = resolveNpmSbomAuditPaths({
  repositoryRoot,
  outputPath: outputPathArgument,
  packageDirectory: packageDirectoryArgument,
});
const packageJson = JSON.parse(readFileSync(paths.packageJsonPath, 'utf8'));
const lockfile = JSON.parse(readFileSync(paths.lockfilePath, 'utf8'));
if (packageJson.name !== NPM_SBOM_TOOLCHAIN_PACKAGE_NAME
  || lockfile.name !== NPM_SBOM_TOOLCHAIN_PACKAGE_NAME
  || lockfile.packages?.['']?.name !== NPM_SBOM_TOOLCHAIN_PACKAGE_NAME) {
  throw new Error('npm audit package identity does not match the locked SBOM toolchain.');
}
const outputPath = paths.outputPath;
const args = ['audit', '--json', '--audit-level=low'];
const npmCliPath = npmCliPathArgument ?? process.env.NPM_CLI_PATH;
const npmVersion = npmCliPath
  ? spawnSync(process.execPath, [resolve(npmCliPath), '--version'], { cwd: paths.packageDirectory })
  : spawnSync('npm', ['--version'], { cwd: paths.packageDirectory, shell: process.platform === 'win32' });
if (npmVersion.error || npmVersion.status !== 0
  || npmVersion.stdout?.toString('utf8').trim() !== SECURITY_POLICY.tools.npm) {
  throw new Error(`npm SBOM toolchain audit requires pinned npm ${SECURITY_POLICY.tools.npm}.`);
}
const result = npmCliPath
  ? spawnSync(process.execPath, [resolve(npmCliPath), ...args], { cwd: paths.packageDirectory })
  : spawnSync('npm', args, { cwd: paths.packageDirectory, shell: process.platform === 'win32' });

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
const summary = verifyNpmSbomToolchainEvidence(packageJson, lockfile, report);
process.stdout.write(`Audited ${summary.lockedPackageCount} locked npm SBOM toolchain dependencies with AJV ${summary.directAjvVersion}.\n`);
