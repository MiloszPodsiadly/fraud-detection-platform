const MAVEN_SCOPES = new Set(['compile', 'provided', 'runtime', 'test', 'system', 'import']);

export function parseMavenPluginResolutionReport(report, source = 'Maven build-plugin inventory') {
  if (typeof report !== 'string') throw new Error(`${source} is not text.`);
  const lines = report.replace(/^\uFEFF/, '').split(/\r?\n/);
  const headerIndex = lines.findIndex((line) => line.trim() === 'The following plugins have been resolved:');
  if (headerIndex < 0) throw new Error(`${source} has no Maven resolved-plugins header.`);

  const plugins = [];
  let currentPlugin = null;
  for (const line of lines.slice(headerIndex + 1)) {
    if (!line.trim()) continue;
    const match = /^(\s*)(\S.*)$/.exec(line);
    const indentation = match?.[1].length;
    const coordinateText = match?.[2];
    const parts = coordinateText?.split(':');
    if (![4, 5].includes(parts?.length)
      || parts.some((part) => !part || /\s/.test(part))) {
      throw new Error(`${source} contains a malformed resolved Maven plugin coordinate.`);
    }
    const [groupId, artifactId, type, version, classifier = ''] = parts;
    const coordinate = { groupId, artifactId, type, version, classifier };
    if (indentation === 3) {
      currentPlugin = { ...coordinate, dependencies: [] };
      plugins.push(currentPlugin);
    } else if (indentation === 6 && currentPlugin) {
      currentPlugin.dependencies.push(coordinate);
    } else {
      throw new Error(`${source} has an unexpected plugin-resolution indentation.`);
    }
  }

  if (plugins.length === 0 || plugins.some((plugin) => plugin.dependencies.length === 0
    || mavenPluginCoordinateKey(plugin.dependencies[0]) !== mavenPluginCoordinateKey(plugin))) {
    throw new Error(`${source} is empty or lacks complete plugin dependency resolution.`);
  }
  return plugins;
}

export function mavenPluginCoordinateKey(coordinate) {
  return JSON.stringify([
    coordinate.groupId, coordinate.artifactId, coordinate.type,
    coordinate.classifier ?? '', coordinate.version,
  ]);
}

export function parseMavenDependencyTree(tree, modulePath, expectedArtifactId) {
  if (!tree || typeof tree !== 'object' || Array.isArray(tree)
    || (tree.children !== undefined && !Array.isArray(tree.children))) {
    throw new Error(`Resolved Maven dependency tree for ${modulePath} has an invalid root.`);
  }
  if (tree.artifactId !== expectedArtifactId) {
    throw new Error(`Resolved Maven dependency tree root for ${modulePath} does not match its POM artifactId.`);
  }
  const root = normalizeCoordinate(tree, `Resolved Maven dependency tree root for ${modulePath}`);
  const occurrences = [];
  let dependencyOccurrenceCount = 0;

  function visit(parent) {
    for (const dependency of parent.children ?? []) {
      if (!dependency || typeof dependency !== 'object' || Array.isArray(dependency)
        || (dependency.children !== undefined && !Array.isArray(dependency.children))) {
        throw new Error(`Resolved Maven dependency tree for ${modulePath} contains an invalid dependency node.`);
      }
      const normalized = normalizeCoordinate(dependency, `Resolved Maven dependency tree for ${modulePath}`);
      if (typeof dependency.scope !== 'string' || !MAVEN_SCOPES.has(dependency.scope)) {
        throw new Error(`Resolved Maven dependency tree for ${modulePath} has incomplete scope metadata.`);
      }
      if (dependency.optional !== undefined
        && ![true, false, 'true', 'false'].includes(dependency.optional)) {
        throw new Error(`Resolved Maven dependency tree for ${modulePath} has an invalid optional flag.`);
      }
      occurrences.push({
        module: modulePath,
        ...normalized,
        scope: dependency.scope,
        optional: dependency.optional === true || dependency.optional === 'true',
      });
      dependencyOccurrenceCount += 1;
      visit(dependency);
    }
  }

  visit(tree);
  return { root, occurrences, dependencyOccurrenceCount };
}

export function mavenCoordinateKey(coordinate) {
  return JSON.stringify([
    coordinate.groupId,
    coordinate.artifactId,
    coordinate.version,
    coordinate.type,
    coordinate.classifier ?? '',
  ]);
}

export function mavenCoordinateFromPurl(purl, source = 'Maven SBOM') {
  const match = /^pkg:maven\/([^/]+)\/([^@/?#]+)@([^?#]+)(?:\?([^#]*))?(?:#.*)?$/.exec(purl ?? '');
  if (!match) throw new Error(`${source} has an invalid Maven package URL: ${purl}`);
  let groupId;
  let artifactId;
  let version;
  try {
    groupId = decodeURIComponent(match[1]);
    artifactId = decodeURIComponent(match[2]);
    version = decodeURIComponent(match[3]);
  } catch {
    throw new Error(`${source} has a malformed encoded Maven package URL.`);
  }
  const qualifiers = new URLSearchParams(match[4] ?? '');
  return mavenCoordinateKey({
    groupId,
    artifactId,
    version,
    type: qualifiers.get('type') ?? 'jar',
    classifier: qualifiers.get('classifier') ?? '',
  });
}

export function verifyMavenSbomCoverage(componentPurls, dependencyCoordinates, reactorCoordinates, source = 'Maven SBOM') {
  const actual = new Set();
  for (const purl of componentPurls) {
    const coordinate = mavenCoordinateFromPurl(purl, source);
    if (actual.has(coordinate)) throw new Error(`${source} contains a duplicate Maven component coordinate.`);
    actual.add(coordinate);
  }
  const expected = new Set(dependencyCoordinates);
  const reactor = new Set(reactorCoordinates);
  const missing = [...expected].filter((coordinate) => !actual.has(coordinate));
  const unaccounted = [...actual].filter((coordinate) => !expected.has(coordinate) && !reactor.has(coordinate));
  if (missing.length > 0 || unaccounted.length > 0) {
    throw new Error(`${source} and resolved reactor dependency trees disagree: ${missing.length} missing dependencies, ${unaccounted.length} unaccounted components.`);
  }
  return { componentCoordinates: [...actual].sort(), dependencyCoordinates: [...expected].sort() };
}

function normalizeCoordinate(value, source) {
  const type = value.type;
  const classifier = value.classifier ?? '';
  if (![value.groupId, value.artifactId, value.version, type].every((field) => typeof field === 'string' && field.length > 0)
    || typeof classifier !== 'string') {
    throw new Error(`${source} has incomplete Maven coordinates, type, or classifier.`);
  }
  return { groupId: value.groupId, artifactId: value.artifactId, version: value.version, type, classifier };
}
