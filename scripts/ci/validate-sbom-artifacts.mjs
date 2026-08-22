#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';

const files = process.argv.slice(2);

if (files.length === 0) {
  throw new Error('At least one SBOM path is required.');
}

const workspace = process.cwd();
const manifest = {
  generatedBy: 'scripts/ci/validate-sbom-artifacts.mjs',
  files: [],
};

for (const file of files) {
  const absolutePath = resolve(file);
  const content = readFileSync(absolutePath, 'utf8').replace(/^\uFEFF/, '');
  const sbom = JSON.parse(content);

  if (sbom.bomFormat !== 'CycloneDX') {
    throw new Error(`${file} is not a CycloneDX SBOM.`);
  }
  if (typeof sbom.specVersion !== 'string' || !sbom.specVersion.startsWith('1.')) {
    throw new Error(`${file} has an unsupported CycloneDX specVersion.`);
  }
  if (!Array.isArray(sbom.components) || sbom.components.length === 0) {
    throw new Error(`${file} does not contain any resolved components.`);
  }
  if (!sbom.metadata || typeof sbom.metadata !== 'object') {
    throw new Error(`${file} does not contain SBOM metadata.`);
  }

  manifest.files.push({
    path: relative(workspace, absolutePath).replaceAll('\\', '/'),
    sha256: createHash('sha256').update(content).digest('hex'),
    bytes: statSync(absolutePath).size,
    specVersion: sbom.specVersion,
    componentCount: sbom.components.length,
  });
}

const manifestPath = resolve('target/sbom/sbom-manifest.json');
mkdirSync(dirname(manifestPath), { recursive: true });
writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
