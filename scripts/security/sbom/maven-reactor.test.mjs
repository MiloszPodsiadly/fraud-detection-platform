#!/usr/bin/env node
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { discoverMavenReactorPoms } from './maven-reactor.mjs';

function withWorkspace(run) {
  const root = mkdtempSync(join(tmpdir(), 'fdp157-maven-reactor-'));
  try {
    run(root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

function writePom(root, path, body) {
  const absolute = join(root, path);
  mkdirSync(join(absolute, '..'), { recursive: true });
  writeFileSync(absolute, `<project><modelVersion>4.0.0</modelVersion>${body}</project>`);
}

test('discovers the declared reactor recursively and ignores unrelated POMs', () => {
  withWorkspace((root) => {
    writePom(root, 'pom.xml', '<modules><module>services</module></modules>');
    writePom(root, 'services/pom.xml', '<modules><module>api</module><module>worker</module></modules>');
    writePom(root, 'services/api/pom.xml', '');
    writePom(root, 'services/worker/pom.xml', '');
    writePom(root, 'unrelated/pom.xml', '');

    assert.deepEqual(discoverMavenReactorPoms(root), [
      'pom.xml', 'services/pom.xml', 'services/api/pom.xml', 'services/worker/pom.xml',
    ]);
  });
});

test('rejects missing or dynamic reactor module paths', () => {
  withWorkspace((root) => {
    writePom(root, 'pom.xml', '<modules><module>${module.path}</module></modules>');
    assert.throws(() => discoverMavenReactorPoms(root), /dynamic or unsafe/i);
  });
  withWorkspace((root) => {
    writePom(root, 'pom.xml', '<modules><module>missing</module></modules>');
    assert.throws(() => discoverMavenReactorPoms(root), /module POM is missing/i);
  });
});

test('rejects duplicate module declarations and workspace escapes', () => {
  withWorkspace((root) => {
    writePom(root, 'pom.xml', '<modules><module>service</module><module>service</module></modules>');
    writePom(root, 'service/pom.xml', '');
    assert.throws(() => discoverMavenReactorPoms(root), /same POM more than once/i);
  });
  withWorkspace((root) => {
    writePom(root, 'pom.xml', '<modules><module>../outside</module></modules>');
    assert.throws(() => discoverMavenReactorPoms(root), /dynamic or unsafe/i);
  });
});
