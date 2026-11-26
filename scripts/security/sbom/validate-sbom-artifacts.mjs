#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { discoverMavenReactorPoms } from './maven-reactor.mjs';
import {
  mavenCoordinateKey,
  parseMavenPluginResolutionReport,
  parseMavenDependencyTree,
  verifyMavenSbomCoverage,
} from './maven-sbom-inventory.mjs';
import { verifyNpmSbomToolchainEvidence } from './verify-npm-sbom-toolchain.mjs';
import {
  verifyNpmInstalledGraph,
  verifyNpmSbomAgainstLockfile,
  verifyPythonSbomAgainstInventory,
} from './sbom-component-inventory.mjs';

const files = process.argv.slice(2);
const testedGitSha = process.env.TESTED_GIT_SHA;
const workspace = process.cwd();

if (!/^[a-f0-9]{40}$/.test(testedGitSha ?? '')) {
  throw new Error('TESTED_GIT_SHA must contain the 40-character commit SHA tested by CI.');
}
const actualGitSha = execFileSync('git', [
  '-c', `safe.directory=${workspace.replaceAll('\\', '/')}`, 'rev-parse', 'HEAD',
], { encoding: 'utf8' }).trim();
if (actualGitSha !== testedGitSha) {
  throw new Error(`SBOM source revision ${actualGitSha} does not match tested Git SHA ${testedGitSha}.`);
}

if (files.length === 0) {
  throw new Error('At least one SBOM path is required.');
}

const manifest = {
  generatedBy: 'scripts/security/run-security-gate.mjs sbom-validation',
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
const sbomsByEcosystem = new Map();

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
  sbomsByEcosystem.set(ecosystem, sbom);

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
  const components = flattenComponents(sbom.components);
  const bomRefs = new Set();
  for (const component of components) {
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
    componentCount: components.length,
    packageUrlType: ecosystem,
    componentPackageUrls: components.map((component) => component.purl).sort(),
  });
}

if (seenFiles.size !== expectedFiles.size || seenEcosystems.size !== expectedFiles.size) {
  const missing = [...expectedFiles.keys()].filter((name) => !seenFiles.has(`target/sbom/${name}`));
  throw new Error(`Resolved SBOM inventory is incomplete; missing: ${missing.join(', ') || [...expectedFiles.values()].filter((name) => !seenEcosystems.has(name)).join(', ')}.`);
}

function flattenComponents(components) {
  const flattened = [];
  const pending = [...components];
  while (pending.length > 0) {
    const component = pending.pop();
    flattened.push(component);
    if (Array.isArray(component?.components)) pending.push(...component.components);
  }
  return flattened;
}

const npmLockPath = resolve('analyst-console-ui/package-lock.json');
const npmLockRaw = readFileSync(npmLockPath);
const npmLockfile = JSON.parse(npmLockRaw.toString('utf8').replace(/^\uFEFF/, ''));
if (npmLockfile.packages?.['']?.dependencies?.ajv !== undefined
  || npmLockfile.packages?.['']?.devDependencies?.ajv !== undefined) {
  throw new Error('The analyst console lockfile unexpectedly includes the independent SBOM toolchain.');
}
const npmSbomComponents = verifyNpmSbomAgainstLockfile(
  sbomsByEcosystem.get('npm'), npmLockfile, 'Resolved npm SBOM',
);
const npmInstalledRoot = resolve(process.env.NPM_INSTALLED_ROOT ?? 'analyst-console-ui');
const npmInstalledRootRelative = relative(workspace, npmInstalledRoot).replaceAll('\\', '/');
if (npmInstalledRootRelative.startsWith('../') || npmInstalledRootRelative === '..') {
  throw new Error('NPM_INSTALLED_ROOT must remain inside the workspace.');
}
const npmInstalledGraph = verifyNpmInstalledGraph(
  npmLockfile, npmInstalledRoot, 'Resolved npm installed graph',
);
manifest.npmLockfile = {
  path: 'analyst-console-ui/package-lock.json',
  sha256: createHash('sha256').update(npmLockRaw).digest('hex'),
  resolvedPackageInstanceCount: npmSbomComponents.length,
  componentIdentities: npmSbomComponents,
  installedGraphPath: npmInstalledRootRelative,
  installedPackageInstanceCount: npmInstalledGraph.identities.length,
  installedComponentIdentities: npmInstalledGraph.identities,
  optionalPackagesAbsentAfterInstall: npmInstalledGraph.omittedOptionalPackages,
};

const toolchainPackagePath = resolve('scripts/security/sbom/npm-sbom-tools/package.json');
const toolchainLockPath = resolve('scripts/security/sbom/npm-sbom-tools/package-lock.json');
const toolchainPackage = JSON.parse(readFileSync(toolchainPackagePath, 'utf8'));
const toolchainLockRaw = readFileSync(toolchainLockPath);
const toolchainLock = JSON.parse(toolchainLockRaw.toString('utf8').replace(/^\uFEFF/, ''));
const npmAuditPath = resolve('target/sbom/npm-sbom-toolchain-audit.json');
if (!existsSync(npmAuditPath)) throw new Error('The independent npm SBOM toolchain audit report is missing.');
const npmAuditRaw = readFileSync(npmAuditPath);
const npmAudit = JSON.parse(npmAuditRaw.toString('utf8').replace(/^\uFEFF/, ''));
const toolchainEvidence = verifyNpmSbomToolchainEvidence(toolchainPackage, toolchainLock, npmAudit);
manifest.npmSbomToolchain = {
  packagePath: 'scripts/security/sbom/npm-sbom-tools/package.json',
  packageLockPath: 'scripts/security/sbom/npm-sbom-tools/package-lock.json',
  packageLockSha256: createHash('sha256').update(toolchainLockRaw).digest('hex'),
  cyclonedxNpmVersion: toolchainPackage.dependencies?.['@cyclonedx/cyclonedx-npm'],
  ajvVersion: toolchainEvidence.directAjvVersion,
  ajvInstallations: toolchainEvidence.ajvInstallations,
  auditReport: {
    path: relative(workspace, npmAuditPath).replaceAll('\\', '/'),
    sha256: createHash('sha256').update(npmAuditRaw).digest('hex'),
    vulnerabilities: toolchainEvidence.vulnerabilities,
    auditedDependencyCount: npmAudit.metadata.dependencies?.total,
    auditReportVersion: toolchainEvidence.auditReportVersion,
  },
};

const sbomPython = process.env.SBOM_PYTHON;
if (!sbomPython) throw new Error('SBOM_PYTHON must identify the target Python environment used for the Python SBOM.');
const pythonInventoryOutput = execFileSync(sbomPython, [
  resolve('scripts/security/sbom/verify-python-sbom-inventory.py'),
  'ml-inference-service/requirements.txt',
  'deployment/python-build-tools-requirements.txt',
], { encoding: 'utf8' });
const pythonInventory = JSON.parse(pythonInventoryOutput);
const pythonSbomComponents = verifyPythonSbomAgainstInventory(
  sbomsByEcosystem.get('pypi'), pythonInventory, 'Resolved Python SBOM',
);
pythonInventory.testedGitSha = testedGitSha;
const pythonInventoryPath = resolve('target/sbom/python-installed-distributions.json');
const pythonInventoryRaw = Buffer.from(`${JSON.stringify(pythonInventory, null, 2)}\n`);
writeFileSync(pythonInventoryPath, pythonInventoryRaw);
manifest.pythonInventory = {
  path: relative(workspace, pythonInventoryPath).replaceAll('\\', '/'),
  sha256: createHash('sha256').update(pythonInventoryRaw).digest('hex'),
  pythonVersion: pythonInventory.pythonVersion,
  markerEnvironment: pythonInventory.markerEnvironment,
  installedDistributionCount: pythonSbomComponents.length,
  componentIdentities: pythonSbomComponents,
  requirementsFiles: pythonInventory.requirementsFiles.map(({ path, sha256, lockedPackages }) => ({
    path, sha256, lockedPackageCount: lockedPackages.length,
  })),
  buildToolScope: pythonInventory.buildToolScope,
};

const mavenModulePoms = discoverMavenReactorPoms(workspace)
  .map((file) => {
    const pom = readFileSync(resolve(file), 'utf8');
    const projectPom = pom.replace(/<parent>[^]*?<\/parent>/, '');
    const artifactId = projectPom.match(/<artifactId>\s*([^<]+)\s*<\/artifactId>/)?.[1]?.trim();
    if (!artifactId) throw new Error(`${file} does not declare an artifactId.`);
    return { path: file.replaceAll('\\', '/'), artifactId };
  });
manifest.mavenModulePoms = mavenModulePoms.sort((left, right) => left.path.localeCompare(right.path));

const mavenDependencyOccurrences = new Map();
const expectedMavenDependencies = new Set();
const reactorModuleCoordinates = new Set();
manifest.mavenDependencyTrees = [];
manifest.mavenBuildPluginResolutions = [];
for (const module of manifest.mavenModulePoms) {
  const treePath = resolve(dirname(resolve(module.path)), 'target/maven-dependency-tree.json');
  if (!existsSync(treePath)) {
    throw new Error(`Resolved Maven dependency tree is missing for ${module.path}: ${relative(workspace, treePath)}.`);
  }
  const rawTree = readFileSync(treePath);
  const tree = JSON.parse(rawTree.toString('utf8').replace(/^\uFEFF/, ''));
  const parsedTree = parseMavenDependencyTree(tree, module.path, module.artifactId);
  reactorModuleCoordinates.add(mavenCoordinateKey(parsedTree.root));
  for (const occurrence of parsedTree.occurrences) {
    expectedMavenDependencies.add(mavenCoordinateKey(occurrence));
    mavenDependencyOccurrences.set(JSON.stringify(occurrence), occurrence);
  }
  manifest.mavenDependencyTrees.push({
    path: relative(workspace, treePath).replaceAll('\\', '/'),
    sha256: createHash('sha256').update(rawTree).digest('hex'),
    bytes: rawTree.byteLength,
    root: parsedTree.root,
    dependencyOccurrenceCount: parsedTree.dependencyOccurrenceCount,
  });

  const pluginOutputPath = resolve(dirname(resolve(module.path)), 'target/maven-plugin-dependencies.txt');
  if (!existsSync(pluginOutputPath)) {
    throw new Error(`Resolved Maven build-plugin inventory is missing for ${module.path}.`);
  }
  const pluginOutputRaw = readFileSync(pluginOutputPath);
  const pluginOutput = pluginOutputRaw.toString('utf8').replace(/^\uFEFF/, '');
  if (pluginOutput.trim().length === 0) {
    throw new Error(`Resolved Maven build-plugin inventory for ${module.path} is empty.`);
  }
  const resolvedPlugins = parseMavenPluginResolutionReport(pluginOutput, `Resolved Maven build-plugin inventory for ${module.path}`);
  manifest.mavenBuildPluginResolutions.push({
    path: relative(workspace, pluginOutputPath).replaceAll('\\', '/'),
    sha256: createHash('sha256').update(pluginOutputRaw).digest('hex'),
    bytes: pluginOutputRaw.byteLength,
    nonEmptyLineCount: pluginOutput.split(/\r?\n/).filter((line) => line.trim().length > 0).length,
    pluginCount: resolvedPlugins.length,
    resolvedCoordinateCount: resolvedPlugins.reduce((total, plugin) => total + plugin.dependencies.length, 0),
    plugins: resolvedPlugins,
  });
}

if (manifest.mavenBuildPluginResolutions.length !== manifest.mavenModulePoms.length) {
  throw new Error('Resolved Maven build-plugin inventory does not cover every reactor POM.');
}

const mavenSbom = manifest.files.find((file) => file.ecosystem === 'maven');
const mavenCoverage = verifyMavenSbomCoverage(
  mavenSbom.componentPackageUrls,
  [...expectedMavenDependencies],
  [...reactorModuleCoordinates],
  mavenSbom.path,
);
manifest.mavenResolvedDependencyCoordinateCount = expectedMavenDependencies.size;
manifest.mavenReactorModuleCoordinateCount = reactorModuleCoordinates.size;
manifest.mavenSbomComponentCoordinates = mavenCoverage.componentCoordinates;
manifest.mavenDependencyOccurrences = [...mavenDependencyOccurrences.values()]
  .sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)));

const manifestPath = resolve('target/sbom/sbom-manifest.json');
mkdirSync(dirname(manifestPath), { recursive: true });
writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
