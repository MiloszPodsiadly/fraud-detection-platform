import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

export function verifyNpmSbomAgainstLockfile(sbom, lockfile, source = 'npm SBOM') {
  if (!lockfile || ![2, 3].includes(lockfile.lockfileVersion) || !lockfile.packages
    || typeof lockfile.packages !== 'object' || Array.isArray(lockfile.packages)) {
    throw new Error(`${source} cannot be checked against a supported npm package lock.`);
  }

  const expected = [];
  for (const [installPath, record] of Object.entries(lockfile.packages)) {
    if (installPath === '' || record?.link === true) continue;
    const name = typeof record?.name === 'string' && record.name.length > 0
      ? record.name
      : npmNameFromInstallPath(installPath);
    if (!name || typeof record?.version !== 'string' || record.version.length === 0) {
      throw new Error(`${source} lockfile entry ${installPath} lacks a resolved name or version.`);
    }
    expected.push(normalizeNpmIdentity(name, record.version));
  }

  const actual = collectPurlIdentities(sbom, 'npm', source);
  assertExactMultiset(actual, expected, source, 'npm package lock');
  return [...expected].sort();
}

export function verifyNpmInstalledGraph(lockfile, projectRoot, source = 'npm installed graph') {
  if (!lockfile || ![2, 3].includes(lockfile.lockfileVersion) || !lockfile.packages
    || typeof lockfile.packages !== 'object' || Array.isArray(lockfile.packages)) {
    throw new Error(`${source} cannot be checked against a supported npm package lock.`);
  }

  const root = resolve(projectRoot);
  const expectedByPath = new Map();
  const omittedOptionalPackages = [];
  for (const [installPath, record] of Object.entries(lockfile.packages)) {
    if (installPath === '' || !installPath.includes('node_modules/')) continue;
    const packagePath = resolve(root, ...installPath.split('/'));
    const packageRelative = relative(root, packagePath);
    if (packageRelative.startsWith(`..${process.platform === 'win32' ? '\\' : '/'}`)
      || packageRelative === '..' || resolve(packagePath) === root) {
      throw new Error(`${source} lockfile contains an unsafe package install path.`);
    }
    const manifestPath = join(packagePath, 'package.json');
    if (record?.link === true) {
      const targetPath = typeof record.resolved === 'string' ? record.resolved.replace(/^file:/, '').replaceAll('\\', '/') : '';
      const targetRecord = lockfile.packages[targetPath];
      if (!targetRecord || typeof targetRecord.version !== 'string') {
        throw new Error(`${source} workspace link ${installPath} has no matching locked target.`);
      }
      if (!existsSync(manifestPath)) throw new Error(`${source} is missing locked workspace link ${installPath}.`);
      const manifest = readPackageManifest(manifestPath, source);
      const targetName = targetRecord.name ?? npmNameFromInstallPath(targetPath);
      const expectedIdentity = normalizeNpmIdentity(targetName, targetRecord.version);
      const actualIdentity = normalizeNpmIdentity(manifest.name, manifest.version);
      if (actualIdentity !== expectedIdentity) {
        throw new Error(`${source} workspace link ${installPath} has ${actualIdentity}, expected ${expectedIdentity}.`);
      }
      expectedByPath.set(installPath, expectedIdentity);
      continue;
    }
    if (!existsSync(manifestPath)) {
      if (record.optional === true) {
        omittedOptionalPackages.push({
          path: installPath,
          name: record.name ?? npmNameFromInstallPath(installPath),
          version: record.version,
        });
        continue;
      }
      throw new Error(`${source} is missing locked package ${installPath}.`);
    }
    const manifest = readPackageManifest(manifestPath, source);
    const lockedName = record.name ?? npmNameFromInstallPath(installPath);
    const expectedIdentity = normalizeNpmIdentity(lockedName, record.version);
    const actualIdentity = normalizeNpmIdentity(manifest.name, manifest.version);
    if (actualIdentity !== expectedIdentity) {
      throw new Error(`${source} package ${installPath} has ${actualIdentity}, expected ${expectedIdentity}.`);
    }
    expectedByPath.set(installPath, actualIdentity);
  }

  const actualByPath = new Map();
  const nodeModulesRoot = join(root, 'node_modules');
  if (existsSync(nodeModulesRoot)) collectInstalledNodeModules(nodeModulesRoot, 'node_modules', actualByPath, source);
  else throw new Error(`${source} has no node_modules directory.`);

  const missing = [...expectedByPath.keys()].filter((path) => !actualByPath.has(path));
  const unexpected = [...actualByPath.keys()].filter((path) => !expectedByPath.has(path));
  const wrongVersions = [...expectedByPath.keys()].filter((path) => actualByPath.has(path)
    && actualByPath.get(path) !== expectedByPath.get(path));
  if (missing.length > 0 || unexpected.length > 0 || wrongVersions.length > 0) {
    throw new Error(`${source} does not match its resolved lockfile: ${missing.length} missing, ${wrongVersions.length} wrong-version, ${unexpected.length} unexpected package paths.`);
  }
  return {
    identities: [...expectedByPath.values()].sort(),
    omittedOptionalPackages: omittedOptionalPackages.sort((left, right) => left.path.localeCompare(right.path)),
  };
}

export function verifyPythonSbomAgainstInventory(sbom, inventory, source = 'Python SBOM') {
  if (!inventory || inventory.schemaVersion !== 1
    || !Array.isArray(inventory.expectedPackages) || !Array.isArray(inventory.installedPackages)
    || !Array.isArray(inventory.requirementsFiles) || !inventory.markerEnvironment) {
    throw new Error(`${source} Python installed inventory is missing or malformed.`);
  }

  const expected = normalizePythonPackages(inventory.expectedPackages, `${source} locked requirements`);
  const installed = normalizePythonPackages(inventory.installedPackages, `${source} installed distributions`);
  assertExactMultiset([...installed.entries()].map(([name, version]) => `${name}@${version}`),
    [...expected.entries()].map(([name, version]) => `${name}@${version}`),
    source, 'hash-locked requirements and installed Python distributions');

  const actual = collectPurlIdentities(sbom, 'pypi', source)
    .map((identity) => normalizePythonIdentity(identity));
  assertExactMultiset(actual, [...installed.entries()].map(([name, version]) => `${name}@${version}`),
    source, 'installed Python distributions');
  return [...installed.entries()].map(([name, version]) => `${name}@${version}`).sort();
}

function normalizePythonPackages(packages, source) {
  const normalized = new Map();
  for (const item of packages) {
    if (!item || typeof item.name !== 'string' || typeof item.version !== 'string' || !item.version) {
      throw new Error(`${source} contains a distribution without an exact name and version.`);
    }
    const name = normalizePythonName(item.name);
    if (!name || normalized.has(name)) throw new Error(`${source} contains a duplicate or invalid distribution ${item.name}.`);
    normalized.set(name, item.version);
  }
  return normalized;
}

function normalizePythonName(name) {
  return name.toLowerCase().replace(/[-_.]+/g, '-');
}

function normalizePythonIdentity(identity) {
  const separator = identity.lastIndexOf('@');
  if (separator < 1) throw new Error(`Invalid PyPI component identity ${identity}.`);
  return `${normalizePythonName(identity.slice(0, separator))}@${identity.slice(separator + 1)}`;
}

function normalizeNpmIdentity(name, version) {
  return `${name.toLowerCase()}@${version}`;
}

function npmNameFromInstallPath(installPath) {
  const marker = 'node_modules/';
  const markerIndex = installPath.lastIndexOf(marker);
  if (markerIndex < 0) return null;
  const suffix = installPath.slice(markerIndex + marker.length).split('/');
  if (!suffix[0]) return null;
  return suffix[0].startsWith('@') && suffix[1] ? `${suffix[0]}/${suffix[1]}` : suffix[0];
}

function collectPurlIdentities(sbom, expectedType, source) {
  if (!sbom || !Array.isArray(sbom.components)) throw new Error(`${source} has no CycloneDX components.`);
  const identities = [];
  const stack = [...sbom.components];
  while (stack.length > 0) {
    const component = stack.pop();
    if (!component || typeof component !== 'object') throw new Error(`${source} contains a malformed component.`);
    if (Array.isArray(component.components)) stack.push(...component.components);
    if (typeof component.purl !== 'string') throw new Error(`${source} contains a component without a package URL.`);
    const match = /^pkg:([^/]+)\/(.+)@([^@/?#]+)(?:\?[^#]*)?(?:#.*)?$/.exec(component.purl);
    if (!match || match[1] !== expectedType) {
      throw new Error(`${source} contains a component outside the ${expectedType} package ecosystem.`);
    }
    let name;
    let version;
    try {
      name = decodeURIComponent(match[2]);
      version = decodeURIComponent(match[3]);
    } catch {
      throw new Error(`${source} contains a malformed encoded package URL.`);
    }
    if (!name || !version) throw new Error(`${source} contains an incomplete package URL.`);
    identities.push(expectedType === 'pypi'
      ? `${normalizePythonName(name)}@${version}`
      : normalizeNpmIdentity(name, version));
  }
  return identities;
}

function assertExactMultiset(actual, expected, source, expectedDescription) {
  const actualCounts = countIdentities(actual);
  const expectedCounts = countIdentities(expected);
  const missing = [];
  const unexpected = [];
  for (const [identity, count] of expectedCounts) {
    const difference = count - (actualCounts.get(identity) ?? 0);
    for (let index = 0; index < difference; index += 1) missing.push(identity);
  }
  for (const [identity, count] of actualCounts) {
    const difference = count - (expectedCounts.get(identity) ?? 0);
    for (let index = 0; index < difference; index += 1) unexpected.push(identity);
  }
  if (missing.length > 0 || unexpected.length > 0) {
    throw new Error(`${source} does not exactly match ${expectedDescription}: ${missing.length} missing (${missing.slice(0, 5).join(', ')}), ${unexpected.length} unexpected (${unexpected.slice(0, 5).join(', ')}).`);
  }
}

function countIdentities(identities) {
  const counts = new Map();
  for (const identity of identities) counts.set(identity, (counts.get(identity) ?? 0) + 1);
  return counts;
}

function collectInstalledNodeModules(directory, installPath, installed, source) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.name === '.bin' || entry.name === '.cache' || entry.name === '.package-lock.json') continue;
    const entryPath = join(directory, entry.name);
    if (entry.name.startsWith('@')) {
      if (!entry.isDirectory()) throw new Error(`${source} contains a malformed scoped package directory.`);
      for (const scopedEntry of readdirSync(entryPath, { withFileTypes: true })) {
        const packagePath = join(entryPath, scopedEntry.name);
        collectInstalledPackage(packagePath, `${installPath}/${entry.name}/${scopedEntry.name}`, installed, source);
      }
      continue;
    }
    collectInstalledPackage(entryPath, `${installPath}/${entry.name}`, installed, source);
  }
}

function collectInstalledPackage(packagePath, installPath, installed, source) {
  const manifestPath = join(packagePath, 'package.json');
  if (!existsSync(manifestPath) || !statSync(packagePath).isDirectory()) {
    throw new Error(`${source} contains a package without a readable manifest at ${installPath}.`);
  }
  const manifest = readPackageManifest(manifestPath, source);
  if (typeof manifest.version !== 'string' || manifest.version.length === 0) {
    throw new Error(`${source} package ${installPath} has no exact version.`);
  }
  if (installed.has(installPath)) throw new Error(`${source} contains a duplicate install path ${installPath}.`);
  installed.set(installPath, normalizeNpmIdentity(manifest.name, manifest.version));
  const nestedNodeModules = join(packagePath, 'node_modules');
  if (existsSync(nestedNodeModules)) collectInstalledNodeModules(nestedNodeModules, `${installPath}/node_modules`, installed, source);
}

function readPackageManifest(path, source) {
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    throw new Error(`${source} contains malformed package metadata at ${relative(process.cwd(), path)}.`);
  }
  if (!manifest || typeof manifest.name !== 'string' || manifest.name.length === 0
    || typeof manifest.version !== 'string' || manifest.version.length === 0) {
    throw new Error(`${source} contains incomplete package metadata at ${relative(process.cwd(), path)}.`);
  }
  return manifest;
}
