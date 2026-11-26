#!/usr/bin/env node
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const script = fileURLToPath(new URL('./write-docker-image-identity.mjs', import.meta.url));
const imageId = `sha256:${'1'.repeat(64)}`;
const manifestDigest = `sha256:${'2'.repeat(64)}`;

function runWriter(image, expectedPlatform = 'linux/amd64') {
  const directory = mkdtempSync(join(tmpdir(), 'fdp157-docker-identity-'));
  try {
    const output = join(directory, 'nested', 'docker-image-inspect.json');
    const result = spawnSync(process.execPath, [script, output, expectedPlatform], {
      encoding: 'utf8',
      input: JSON.stringify([image]),
    });
    return { result, output: result.status === 0 ? JSON.parse(readFileSync(output, 'utf8')) : null };
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

test('writes only the allowlisted Docker identity fields and a reviewed descriptor projection', () => {
  const { result, output } = runWriter({
    Id: imageId,
    Os: 'linux',
    Architecture: 'amd64',
    RepoTags: ['fdp-local/service:tested'],
    RepoDigests: ['registry.example/service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'],
    Descriptor: {
      digest: manifestDigest,
      mediaType: 'application/vnd.oci.image.manifest.v1+json',
      size: 42,
      annotations: {
        'config.digest': imageId,
        'org.opencontainers.image.title': 'private build label',
      },
      unknown: 'private descriptor field',
    },
    Config: {
      Env: ['TOKEN=must-not-be-persisted'],
      Labels: { 'internal.label': 'must-not-be-persisted' },
    },
    RootFS: { Layers: ['private-layer-detail'] },
  });

  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.deepEqual(Object.keys(output[0]).sort(), ['Architecture', 'Descriptor', 'Id', 'Os', 'RepoDigests', 'RepoTags']);
  assert.deepEqual(output[0].Descriptor, {
    digest: manifestDigest,
    mediaType: 'application/vnd.oci.image.manifest.v1+json',
    size: 42,
    annotations: { 'config.digest': imageId },
  });
  assert.doesNotMatch(JSON.stringify(output), /TOKEN|private|RootFS|Labels/);
});

test('fails closed on a wrong Engine platform', () => {
  const { result } = runWriter({ Id: imageId, Os: 'linux', Architecture: 'arm64' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /platform linux\/arm64 does not match linux\/amd64/i);
});

test('fails closed when inspect returns more than one image', () => {
  const directory = mkdtempSync(join(tmpdir(), 'fdp157-docker-identity-'));
  try {
    const output = join(directory, 'docker-image-inspect.json');
    const image = { Id: imageId, Os: 'linux', Architecture: 'amd64' };
    const result = spawnSync(process.execPath, [script, output, 'linux/amd64'], {
      encoding: 'utf8',
      input: JSON.stringify([image, image]),
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /exactly one inspected image/i);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
