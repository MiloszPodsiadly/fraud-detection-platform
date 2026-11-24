#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';

const files = process.argv.slice(2);
const testedGitSha = process.env.TESTED_GIT_SHA;

if (!/^[a-f0-9]{40}$/.test(testedGitSha ?? '')) {
  throw new Error('TESTED_GIT_SHA must contain the 40-character commit SHA tested by CI.');
}
const actualGitSha = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
if (actualGitSha !== testedGitSha) {
  throw new Error(`SBOM source revision ${actualGitSha} does not match tested Git SHA ${testedGitSha}.`);
}

if (files.length === 0) {
  throw new Error('At least one SBOM path is required.');
}

const workspace = process.cwd();
const manifest = {
  generatedBy: 'scripts/ci/validate-sbom-artifacts.mjs',
  testedGitSha,
  expectedEcosystems: ['maven', 'npm', 'pypi'],
  files: [],
};

const expectedFiles = new Map([
  ['maven-resolved.cdx.json', 'maven'],
  ['npm-analyst-console-ui.cdx.json', 'npm'],
  ['python-ml-inference-service.cdx.json', 'pypi'],
]);
if (files.length !== expectedFiles.size) {
  throw new Error(`Expected exactly ${expectedFiles.size} resolved SBOM files, found ${files.length}.`);
}

const seenFiles = new Set();
const seenEcosystems = new Set();

for (const file of files) {
  const absolutePath = resolve(file);
  const relativePath = relative(workspace, absolutePath).replaceAll('\\', '/');
  const basename = relativePath.split('/').at(-1);
  const ecosystem = expectedFiles.get(basename);
  if (!ecosystem || relativePath !== `target/sbom/${basename}`) {
    throw new Error(`${file} is outside the expected resolved SBOM inventory.`);
  }
  if (seenFiles.has(relativePath) || seenEcosystems.has(ecosystem)) {
    throw new Error(`${file} duplicates a resolved SBOM artifact or ecosystem.`);
  }
  seenFiles.add(relativePath);
  seenEcosystems.add(ecosystem);

  const raw = readFileSync(absolutePath);
  const content = raw.toString('utf8').replace(/^\uFEFF/, '');
  const sbom = JSON.parse(content);

  if (sbom.bomFormat !== 'CycloneDX') {
    throw new Error(`${file} is not a CycloneDX SBOM.`);
  }
  if (sbom.specVersion !== '1.6' || !Number.isInteger(sbom.version) || sbom.version < 1) {
    throw new Error(`${file} must be a versioned CycloneDX 1.6 BOM.`);
  }
  if (!Array.isArray(sbom.components) || sbom.components.length === 0) {
    throw new Error(`${file} does not contain any resolved components.`);
  }
  if (!sbom.metadata || typeof sbom.metadata !== 'object') {
    throw new Error(`${file} does not contain SBOM metadata.`);
  }

  const expectedPurlPrefix = `pkg:${ecosystem}/`;
  const bomRefs = new Set();
  for (const component of sbom.components) {
    if (!component || typeof component.name !== 'string' || component.name.length === 0
      || typeof component.type !== 'string' || component.type.length === 0
      || typeof component.version !== 'string' || component.version.length === 0) {
      throw new Error(`${file} contains a component without its required name, type, or version.`);
    }
    if (typeof component.purl !== 'string' || !component.purl.startsWith(expectedPurlPrefix)) {
      throw new Error(`${file} contains a component outside the expected ${ecosystem} package ecosystem.`);
    }
    if (typeof component['bom-ref'] === 'string') {
      if (bomRefs.has(component['bom-ref'])) throw new Error(`${file} contains a duplicate bom-ref.`);
      bomRefs.add(component['bom-ref']);
    }
  }

  const metadataComponent = sbom.metadata.component;
  if (metadataComponent !== undefined) {
    if (!metadataComponent || typeof metadataComponent.name !== 'string' || !metadataComponent.name
      || typeof metadataComponent.type !== 'string' || !metadataComponent.type) {
      throw new Error(`${file} has an invalid CycloneDX metadata component.`);
    }
    if (metadataComponent.purl !== undefined
      && (typeof metadataComponent.purl !== 'string' || !metadataComponent.purl.startsWith(expectedPurlPrefix))) {
      throw new Error(`${file} metadata component does not identify the expected ${ecosystem} ecosystem.`);
    }
  }

  manifest.files.push({
    path: relativePath,
    ecosystem,
    sha256: createHash('sha256').update(raw).digest('hex'),
    bytes: raw.byteLength,
    specVersion: sbom.specVersion,
    componentCount: sbom.components.length,
    packageUrlType: ecosystem,
    componentPackageUrls: sbom.components.map((component) => component.purl).sort(),
  });
}

if (seenFiles.size !== expectedFiles.size || seenEcosystems.size !== expectedFiles.size) {
  const missing = [...expectedFiles.keys()].filter((name) => !seenFiles.has(`target/sbom/${name}`));
  throw new Error(`Resolved SBOM inventory is incomplete; missing: ${missing.join(', ') || [...expectedFiles.values()].filter((name) => !seenEcosystems.has(name)).join(', ')}.`);
}

const mavenModulePoms = execFileSync('git', ['ls-files', '--', 'pom.xml', '**/pom.xml'], { encoding: 'utf8' })
  .split(/\r?\n/)
  .filter(Boolean)
  .map((file) => {
    const pom = readFileSync(resolve(file), 'utf8');
    const projectPom = pom.replace(/<parent>[^]*?<\/parent>/, '');
    const artifactId = projectPom.match(/<artifactId>\s*([^<]+)\s*<\/artifactId>/)?.[1]?.trim();
    if (!artifactId) throw new Error(`${file} does not declare an artifactId.`);
    return { path: file.replaceAll('\\', '/'), artifactId };
  });
manifest.mavenModulePoms = mavenModulePoms.sort((left, right) => left.path.localeCompare(right.path));

const manifestPath = resolve('target/sbom/sbom-manifest.json');
mkdirSync(dirname(manifestPath), { recursive: true });
writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
