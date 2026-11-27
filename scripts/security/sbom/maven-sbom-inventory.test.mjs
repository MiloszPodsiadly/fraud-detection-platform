import assert from 'node:assert/strict';
import test from 'node:test';
import {
  mavenCoordinateKey,
  parseMavenDependencyTree,
  parseMavenPluginResolutionReport,
  verifyMavenSbomCoverage,
  verifyMavenTrivyPackageCoverage,
} from './maven-sbom-inventory.mjs';

const reactorCoordinate = {
  groupId: 'com.example', artifactId: 'service', version: '1.0.0', type: 'jar', classifier: '',
};
const dependency = {
  groupId: 'org.example', artifactId: 'library', version: '2.4.1', type: 'jar',
  scope: 'runtime', optional: false,
};
const tree = {
  ...reactorCoordinate,
  children: [{ ...dependency, children: [{
    groupId: 'org.example', artifactId: 'transitive', version: '5.0', type: 'jar',
    scope: 'test', classifier: 'tests', optional: true,
  }] }],
};

test('Maven tree inventory retains recursive coordinates, scopes, classifiers, and optional state', () => {
  const parsed = parseMavenDependencyTree(tree, 'service/pom.xml', 'service');
  assert.equal(parsed.dependencyOccurrenceCount, 2);
  assert.deepEqual(parsed.occurrences.map(({ scope }) => scope), ['runtime', 'test']);
  assert.equal(parsed.occurrences[1].classifier, 'tests');
  assert.equal(parsed.occurrences[1].optional, true);
});

test('Maven tree inventory rejects absent scope, type, classifier shape, and wrong module root', () => {
  assert.throws(() => parseMavenDependencyTree({
    ...tree, children: [{ ...dependency, scope: undefined }],
  }, 'service/pom.xml', 'service'), /scope metadata/);
  assert.throws(() => parseMavenDependencyTree({
    ...tree, children: [{ ...dependency, type: undefined }],
  }, 'service/pom.xml', 'service'), /coordinates, type, or classifier/);
  assert.throws(() => parseMavenDependencyTree({
    ...tree, children: [{ ...dependency, classifier: 7 }],
  }, 'service/pom.xml', 'service'), /coordinates, type, or classifier/);
  assert.throws(() => parseMavenDependencyTree(tree, 'service/pom.xml', 'other'), /does not match/);
});

test('Maven build-plugin inventory retains plugin roots and resolved dependencies', () => {
  const plugins = parseMavenPluginResolutionReport([
    'The following plugins have been resolved:',
    '   org.apache.maven.plugins:maven-compiler-plugin:jar:3.15.0',
    '      org.apache.maven.plugins:maven-compiler-plugin:jar:3.15.0',
    '      org.codehaus.plexus:plexus-compiler-api:jar:2.16.2',
  ].join('\n'));
  assert.equal(plugins.length, 1);
  assert.equal(plugins[0].dependencies.length, 2);
  assert.equal(plugins[0].dependencies[1].artifactId, 'plexus-compiler-api');
  assert.throws(() => parseMavenPluginResolutionReport(''), /no Maven resolved-plugins header/);
  assert.throws(() => parseMavenPluginResolutionReport([
    'The following plugins have been resolved:',
    '   org.apache.maven.plugins:maven-compiler-plugin:jar:3.15.0',
    '      malformed-coordinate',
  ].join('\n')), /malformed resolved Maven plugin coordinate/);
  assert.throws(() => parseMavenPluginResolutionReport([
    'The following plugins have been resolved:',
    '   org.apache.maven.plugins:maven-compiler-plugin:jar:3.15.0',
    '      org.apache.maven.plugins:maven-surefire-plugin:jar:3.6.1',
  ].join('\n')), /lacks complete plugin dependency resolution/);
});

test('Maven SBOM must cover all resolved coordinates and contain no unexpected or duplicate components', () => {
  const dependencyKey = mavenCoordinateKey(dependency);
  const purl = 'pkg:maven/org.example/library@2.4.1';
  const transitivePurl = 'pkg:maven/org.example/transitive@5.0?classifier=tests';
  const transitiveKey = mavenCoordinateKey({
    groupId: 'org.example', artifactId: 'transitive', version: '5.0', type: 'jar', classifier: 'tests',
  });
  const reactorKey = mavenCoordinateKey(reactorCoordinate);
  const resolved = [dependencyKey, transitiveKey];
  const reactor = [reactorKey];
  assert.equal(verifyMavenSbomCoverage([purl, transitivePurl], resolved, reactor).componentCoordinates.length, 2);
  assert.throws(() => verifyMavenSbomCoverage([purl], resolved, reactor), /1 missing dependencies/);
  assert.throws(() => verifyMavenSbomCoverage([
    purl, transitivePurl, 'pkg:maven/org.example/unexpected@9.9',
  ], resolved, reactor), /1 unaccounted components/);
  assert.throws(() => verifyMavenSbomCoverage([purl, purl], [dependencyKey], reactor), /duplicate Maven component/);
  assert.throws(() => verifyMavenSbomCoverage(['pkg:maven/%ZZ/library@1'], [], reactor), /malformed encoded/);
});

test('Trivy Maven scan must cover resolved SBOM and build-plugin JAR coordinates', () => {
  const plugin = {
    groupId: 'org.example', artifactId: 'build-plugin', type: 'jar', version: '3.1.0', classifier: '',
  };
  const report = verifyMavenTrivyPackageCoverage([
    'pkg:maven/org.example/library@2.4.1',
    'pkg:maven/org.example/build-plugin@3.1.0',
  ], ['pkg:maven/org.example/library@2.4.1'], [
    { plugins: [{ dependencies: [plugin] }] },
  ]);
  assert.deepEqual(report, {
    resolvedJarPackageCount: 2,
    scannedJarPackageCount: 2,
    coveredJarPackageCount: 2,
  });
  assert.throws(() => verifyMavenTrivyPackageCoverage(
    ['pkg:maven/org.example/library@2.4.1'],
    ['pkg:maven/org.example/library@2.4.1'],
    [{ plugins: [{ dependencies: [plugin] }] }],
  ), /missing 1 independently resolved Maven JAR package/);
});
