#!/usr/bin/env node
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test, { after } from 'node:test';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const fixtureRoot = mkdtempSync(join(tmpdir(), 'fdp157-image-coverage-'));

function copyRelevantRepositoryFiles(fixtureRoot) {
  for (const directory of ['.github', 'deployment', 'scripts/ci']) {
    cpSync(join(repositoryRoot, directory), join(fixtureRoot, directory), { recursive: true });
  }
  cpSync(join(repositoryRoot, 'pom.xml'), join(fixtureRoot, 'pom.xml'));

  function copyJavaTests(directory) {
    if (!existsSync(directory)) return;
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const source = join(directory, entry.name);
      if (entry.isDirectory()) copyJavaTests(source);
      else if (entry.isFile() && entry.name.endsWith('.java')) {
        const destination = join(fixtureRoot, relative(repositoryRoot, source));
        mkdirSync(dirname(destination), { recursive: true });
        cpSync(source, destination);
      }
    }
  }

  for (const entry of readdirSync(repositoryRoot, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    const module = join(repositoryRoot, entry.name);
    copyJavaTests(join(module, 'src', 'test', 'java'));
  }
  copyJavaTests(join(repositoryRoot, 'common-test-support', 'src', 'main', 'java'));

  function copyDockerfiles(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (entry.name.startsWith('.') || entry.name === 'node_modules' || entry.name === 'target'
        || entry.name === '.venv' || entry.name === '.venv-sbom-ml') continue;
      const source = join(directory, entry.name);
      if (entry.isDirectory()) {
        copyDockerfiles(source);
      } else if (entry.isFile() && (/^Dockerfile(?:$|[._-])/.test(entry.name) || /\.dockerfile$/i.test(entry.name))) {
        const destination = join(fixtureRoot, relative(repositoryRoot, source));
        if (existsSync(destination)) continue;
        mkdirSync(dirname(destination), { recursive: true });
        cpSync(source, destination);
      }
    }
  }
  copyDockerfiles(repositoryRoot);
}

copyRelevantRepositoryFiles(fixtureRoot);

after(() => rmSync(fixtureRoot, { recursive: true, force: true }));

function runCoverage(mutate = () => () => {}) {
  const cleanup = mutate(fixtureRoot) ?? (() => {});
  try {
    return spawnSync(process.execPath, [join(fixtureRoot, 'scripts', 'ci', 'check-image-scan-coverage.mjs')], {
      cwd: fixtureRoot,
      encoding: 'utf8',
      maxBuffer: 16 * 1024 * 1024,
      env: { ...process.env, TESTED_GIT_SHA: '', GITHUB_SHA: '' },
    });
  } finally {
    cleanup();
  }
}

test('accepts the complete repository image inventory in one image security job', () => {
  const result = runCoverage();
  assert.equal(result.status, 0, result.stderr || result.stdout);
});

test('rejects a floating image reference introduced in a Docker build stage', () => {
  const result = runCoverage((root) => {
    const file = join(root, 'deployment', 'Dockerfile.mutation');
    writeFileSync(file, 'FROM alpine:latest\n', 'utf8');
    return () => rmSync(file, { force: true });
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /not pinned|floating/i);
});

test('rejects a source image missing from the image security job', () => {
  const result = runCoverage((root) => {
    const workflowPath = join(root, '.github', 'workflows', 'docker-compose-ci.yml');
    const workflow = readFileSync(workflowPath, 'utf8');
    const withoutMongoScan = workflow.replace(
      /^[ \t]*image-ref: mongo:7\.0\.48@sha256:[a-f0-9]{64}\r?\n/m,
      '',
    );
    assert.notEqual(withoutMongoScan, workflow, 'expected to locate the MongoDB scan input');
    writeFileSync(workflowPath, withoutMongoScan, 'utf8');
    return () => writeFileSync(workflowPath, workflow, 'utf8');
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /missing from external image scan matrix/i);
});

test('rejects a Docker build action that produces an image outside the scan inventory', () => {
  const result = runCoverage((root) => {
    const actionPath = join(root, '.github', 'actions', 'build-kafka-test-image', 'action.yml');
    const action = readFileSync(actionPath, 'utf8');
    const changedAction = action.replace(
      'fdp-local/kafka:4.4.0-temurin-25.0.5.1_2-r2',
      'fdp-local/kafka:4.4.0-unreviewed',
    );
    assert.notEqual(changedAction, action, 'expected to locate the Kafka build tag');
    writeFileSync(actionPath, changedAction, 'utf8');
    return () => writeFileSync(actionPath, action, 'utf8');
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /not pinned|missing from external image scan matrix/i);
});

test('rejects a Kafka image that no longer checksum-pins the patched compression libraries', () => {
  const result = runCoverage((root) => {
    const dockerfilePath = join(root, 'deployment', 'Dockerfile.kafka');
    const dockerfile = readFileSync(dockerfilePath, 'utf8');
    const withoutSnappyPin = dockerfile.replace(
      "echo '1c03ce77b3f2e926c25a952612e372404d80bc88c6c8052bede964804fabd732  /tmp/snappy-java-1.1.10.12.jar' | sha256sum -c -",
      "echo 'unverified  /tmp/snappy-java-1.1.10.12.jar' | sha256sum -c -",
    );
    assert.notEqual(withoutSnappyPin, dockerfile, 'expected to locate the Snappy SHA-256 verification');
    writeFileSync(dockerfilePath, withoutSnappyPin, 'utf8');
    return () => writeFileSync(dockerfilePath, dockerfile, 'utf8');
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /checksum-pinned 1\.1\.10\.12/i);
});

test('rejects an image security job that omits the policy publisher image', () => {
  const result = runCoverage((root) => {
    const workflowPath = join(root, '.github', 'workflows', 'docker-compose-ci.yml');
    const workflow = readFileSync(workflowPath, 'utf8');
    const withoutPublisher = workflow.replace(/^ {10}artifact-name: backend-image-model-promotion-policy-publisher-security\r?\n/m, '');
    assert.notEqual(withoutPublisher, workflow, 'expected to locate the publisher scan artifact');
    writeFileSync(workflowPath, withoutPublisher, 'utf8');
    return () => writeFileSync(workflowPath, workflow, 'utf8');
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /missing required application image model-promotion-policy-publisher/i);
});

test('rejects unresolved variables in Compose image references', () => {
  const result = runCoverage((root) => {
    const file = join(root, 'deployment', 'docker-compose-mutation.yml');
    writeFileSync(file, 'services:\n  injected:\n    image: ${UNVERIFIED_IMAGE}\n', 'utf8');
    return () => rmSync(file, { force: true });
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /unresolved dynamic image reference/i);
});

test('rejects a dynamically introduced Testcontainers image without a scan binding', () => {
  const result = runCoverage((root) => {
    const sourcePath = join(root, 'alert-service', 'src', 'test', 'java', 'com', 'example', 'NewImageFixture.java');
    mkdirSync(dirname(sourcePath), { recursive: true });
    writeFileSync(sourcePath, [
      'import org.testcontainers.utility.DockerImageName;',
      'class NewImageFixture {',
      '  private static final String image = "registry.example/new-helper@sha256:' + 'a'.repeat(64) + '";',
      '  DockerImageName imageName() { return DockerImageName.parse(image); }',
      '}',
      '',
    ].join('\n'), 'utf8');
    return () => rmSync(sourcePath, { force: true });
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /unresolved Testcontainers image expression image/i);
});
