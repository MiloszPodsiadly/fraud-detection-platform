import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { NPM_SBOM_TOOLCHAIN_DIRECTORY, resolveNpmSbomAuditPaths } from './npm-sbom-audit-paths.mjs';

function createWorkspace() {
  const repositoryRoot = mkdtempSync(path.join(os.tmpdir(), 'fdp sbom audit workspace '));
  const packageDirectory = path.join(repositoryRoot, ...NPM_SBOM_TOOLCHAIN_DIRECTORY.split('/'));
  mkdirSync(packageDirectory, { recursive: true });
  writeFileSync(path.join(packageDirectory, 'package.json'), '{}\n');
  writeFileSync(path.join(packageDirectory, 'package-lock.json'), '{}\n');
  return { repositoryRoot, packageDirectory };
}

test('resolves an absolute report path and the intended lockfile workspace when repository paths contain spaces', () => {
  const workspace = createWorkspace();
  try {
    const outputPath = path.join(workspace.repositoryRoot, 'target', 'sbom', 'toolchain audit.json');
    const paths = resolveNpmSbomAuditPaths({
      repositoryRoot: workspace.repositoryRoot,
      outputPath,
      packageDirectory: workspace.packageDirectory,
    });
    assert.equal(paths.packageDirectory, workspace.packageDirectory);
    assert.equal(paths.lockfilePath, path.join(workspace.packageDirectory, 'package-lock.json'));
    assert.equal(paths.outputPath, outputPath);
  } finally {
    rmSync(workspace.repositoryRoot, { recursive: true, force: true });
  }
});

test('rejects the repository root, frontend workspace, and any unrelated npm package directory', () => {
  const workspace = createWorkspace();
  try {
    for (const packageDirectory of [workspace.repositoryRoot, path.join(workspace.repositoryRoot, 'analyst-console-ui')]) {
      assert.throws(() => resolveNpmSbomAuditPaths({
        repositoryRoot: workspace.repositoryRoot,
        outputPath: path.join(workspace.repositoryRoot, 'target', 'sbom', 'audit.json'),
        packageDirectory,
      }), /must be the locked/);
    }
  } finally {
    rmSync(workspace.repositoryRoot, { recursive: true, force: true });
  }
});

test('rejects missing manifests and output paths outside target/sbom', () => {
  const workspace = createWorkspace();
  try {
    const lockfilePath = path.join(workspace.packageDirectory, 'package-lock.json');
    rmSync(lockfilePath);
    assert.throws(() => resolveNpmSbomAuditPaths({
      repositoryRoot: workspace.repositoryRoot,
      outputPath: path.join(workspace.repositoryRoot, 'target', 'sbom', 'audit.json'),
      packageDirectory: workspace.packageDirectory,
    }), /requires the package manifest and lockfile/);
    writeFileSync(lockfilePath, '{}\n');
    assert.throws(() => resolveNpmSbomAuditPaths({
      repositoryRoot: workspace.repositoryRoot,
      outputPath: path.join(workspace.repositoryRoot, 'audit.json'),
      packageDirectory: workspace.packageDirectory,
    }), /under the repository target\/sbom/);
  } finally {
    rmSync(workspace.repositoryRoot, { recursive: true, force: true });
  }
});
