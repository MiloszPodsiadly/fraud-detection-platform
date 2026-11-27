#!/usr/bin/env node
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  verifyNpmInstalledGraph,
  verifyNpmSbomAgainstLockfile,
  verifyPythonSbomAgainstInventory,
} from './sbom-component-inventory.mjs';

function npmFixture() {
  const lockfile = {
    lockfileVersion: 3,
    packages: {
      '': { dependencies: { rootdep: '1.0.0' } },
      'node_modules/rootdep': { version: '1.0.0' },
      'node_modules/nested': { version: '2.0.0', optional: true, os: ['win32'] },
      'node_modules/parent/node_modules/nested': { version: '1.0.0', dev: true },
      'node_modules/alias-name': { name: 'actual-package-name', version: '3.0.0', optional: true },
      'node_modules/@acme/workspace-link': { resolved: 'packages/workspace', link: true },
      'packages/workspace': { name: '@acme/workspace-link', version: '4.0.0' },
    },
  };
  const sbom = {
    components: [
      { purl: 'pkg:npm/rootdep@1.0.0' },
      { purl: 'pkg:npm/nested@2.0.0' },
      { purl: 'pkg:npm/nested@1.0.0' },
      { purl: 'pkg:npm/actual-package-name@3.0.0' },
      { purl: 'pkg:npm/%40acme%2Fworkspace-link@4.0.0' },
    ],
  };
  return { lockfile, sbom };
}

function pythonFixture() {
  return {
    inventory: {
      schemaVersion: 1,
      requirementsFiles: [
        { path: 'ml-inference-service/requirements.txt', sha256: 'a'.repeat(64) },
        { path: 'deployment/python-build-tools-requirements.txt', sha256: 'b'.repeat(64) },
      ],
      markerEnvironment: { python_version: '3.12', sys_platform: 'linux' },
      expectedPackages: [
        { name: 'PyJWT', version: '2.15.1' },
        { name: 'requests', version: '2.32.5' },
        { name: 'pip', version: '26.3' },
        { name: 'setuptools', version: '84.2.1' },
      ],
      installedPackages: [
        { name: 'pyjwt', version: '2.15.1' },
        { name: 'requests', version: '2.32.5' },
        { name: 'PIP', version: '26.3' },
        { name: 'setuptools', version: '84.2.1' },
      ],
    },
    sbom: {
      components: [
        { purl: 'pkg:pypi/pyjwt@2.15.1' },
        { purl: 'pkg:pypi/requests@2.32.5' },
        { purl: 'pkg:pypi/pip@26.3' },
        { purl: 'pkg:pypi/setuptools@84.2.1' },
      ],
    },
  };
}

test('matches npm SBOM to all locked install instances including nested, optional, platform, alias and workspace entries', () => {
  const { lockfile, sbom } = npmFixture();
  assert.deepEqual(verifyNpmSbomAgainstLockfile(sbom, lockfile).sort(), [
    '@acme/workspace-link@4.0.0',
    'actual-package-name@3.0.0',
    'nested@1.0.0',
    'nested@2.0.0',
    'rootdep@1.0.0',
  ]);
});

test('rejects missing, wrong-version, unexpected, and duplicate npm SBOM components', () => {
  const { lockfile, sbom } = npmFixture();
  assert.throws(() => verifyNpmSbomAgainstLockfile({ components: sbom.components.slice(1) }, lockfile), /missing/);
  assert.throws(() => verifyNpmSbomAgainstLockfile({
    components: sbom.components.map((component) => component.purl === 'pkg:npm/rootdep@1.0.0'
      ? { purl: 'pkg:npm/rootdep@9.0.0' } : component),
  }, lockfile), /missing.*unexpected/);
  assert.throws(() => verifyNpmSbomAgainstLockfile({ components: [...sbom.components, sbom.components[0]] }, lockfile), /unexpected/);
});

test('matches the installed npm graph to lock paths, nested instances, aliases, and workspaces', (context) => {
  const root = mkdtempSync(join(tmpdir(), 'fdp157-npm-installed-'));
  context.after(() => rmSync(root, { recursive: true, force: true }));
  const lockfile = {
    lockfileVersion: 3,
    packages: {
      '': {},
      'node_modules/rootdep': { version: '1.0.0' },
      'node_modules/parent': { version: '3.0.0' },
      'node_modules/parent/node_modules/nested': { version: '2.0.0' },
      'node_modules/alias-name': { name: 'actual-package-name', version: '4.0.0' },
      'node_modules/@acme/workspace-link': { resolved: 'packages/workspace', link: true },
      'packages/workspace': { name: '@acme/workspace-link', version: '5.0.0' },
      'node_modules/linux-only': { version: '1.0.0', optional: true, os: ['linux'] },
    },
  };
  addInstalledPackage(root, 'node_modules/rootdep', 'rootdep', '1.0.0');
  addInstalledPackage(root, 'node_modules/parent', 'parent', '3.0.0');
  addInstalledPackage(root, 'node_modules/parent/node_modules/nested', 'nested', '2.0.0');
  addInstalledPackage(root, 'node_modules/alias-name', 'actual-package-name', '4.0.0');
  addInstalledPackage(root, 'node_modules/@acme/workspace-link', '@acme/workspace-link', '5.0.0');
  const inventory = verifyNpmInstalledGraph(lockfile, root, 'npm installed test');
  assert.equal(inventory.identities.length, 5);
  assert.deepEqual(inventory.omittedOptionalPackages.map(({ name }) => name), ['linux-only']);
});

test('rejects missing, wrong-version, and unexpected npm installed packages', (context) => {
  const root = mkdtempSync(join(tmpdir(), 'fdp157-npm-installed-invalid-'));
  context.after(() => rmSync(root, { recursive: true, force: true }));
  const lockfile = {
    lockfileVersion: 3,
    packages: { '': {}, 'node_modules/rootdep': { version: '1.0.0' } },
  };
  addInstalledPackage(root, 'node_modules/rootdep', 'rootdep', '1.0.0');
  assert.deepEqual(verifyNpmInstalledGraph(lockfile, root).identities, ['rootdep@1.0.0']);

  rmSync(join(root, 'node_modules/rootdep'), { recursive: true, force: true });
  assert.throws(() => verifyNpmInstalledGraph(lockfile, root), /missing locked package/);
  addInstalledPackage(root, 'node_modules/rootdep', 'rootdep', '2.0.0');
  assert.throws(() => verifyNpmInstalledGraph(lockfile, root), /expected rootdep@1.0.0/);

  rmSync(join(root, 'node_modules/rootdep'), { recursive: true, force: true });
  addInstalledPackage(root, 'node_modules/rootdep', 'rootdep', '1.0.0');
  addInstalledPackage(root, 'node_modules/extra', 'extra', '1.0.0');
  assert.throws(() => verifyNpmInstalledGraph(lockfile, root), /unexpected package paths/);
});

test('matches Python SBOM to normalized, versioned installed packages and hash-lock inventory', () => {
  const { inventory, sbom } = pythonFixture();
  assert.deepEqual(verifyPythonSbomAgainstInventory(sbom, inventory).sort(), [
    'pip@26.3', 'pyjwt@2.15.1', 'requests@2.32.5', 'setuptools@84.2.1',
  ]);
});

test('rejects Python lock/install drift and missing, wrong-version, or unexpected SBOM components', () => {
  const { inventory, sbom } = pythonFixture();
  const changedInstall = structuredClone(inventory);
  changedInstall.installedPackages[0].version = '2.15.0';
  assert.throws(() => verifyPythonSbomAgainstInventory(sbom, changedInstall), /does not exactly match hash-locked requirements/);

  assert.throws(() => verifyPythonSbomAgainstInventory({ components: sbom.components.slice(1) }, inventory), /missing/);
  assert.throws(() => verifyPythonSbomAgainstInventory({
    components: sbom.components.map((component) => component.purl === 'pkg:pypi/pyjwt@2.15.1'
      ? { purl: 'pkg:pypi/pyjwt@2.15.0' } : component),
  }, inventory), /missing.*unexpected/);
  assert.throws(() => verifyPythonSbomAgainstInventory({
    components: [...sbom.components, { purl: 'pkg:pypi/rogue@1.0.0' }],
  }, inventory), /unexpected/);
});

function addInstalledPackage(root, installPath, name, version) {
  const packageDirectory = join(root, ...installPath.split('/'));
  mkdirSync(packageDirectory, { recursive: true });
  writeFileSync(join(packageDirectory, 'package.json'), JSON.stringify({ name, version }));
}
