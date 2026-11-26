import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { KAFKA_TEST_IMAGE, validateKafkaTestImageEvidence } from './verify-kafka-test-image.mjs';

const manifestDigest = `sha256:${'a'.repeat(64)}`;
const configDigest = `sha256:${'b'.repeat(64)}`;
const metadata = JSON.stringify({
  'containerimage.digest': manifestDigest,
  'containerimage.config.digest': configDigest,
  'containerimage.descriptor': {
    digest: manifestDigest,
    mediaType: 'application/vnd.oci.image.manifest.v1+json',
    annotations: { 'config.digest': configDigest },
  },
});
const dockerfileText = readFileSync(new URL('../../../deployment/Dockerfile.kafka', import.meta.url), 'utf8');

function validEvidence(overrides = {}) {
  return {
    imageRef: KAFKA_TEST_IMAGE,
    buildDigest: manifestDigest,
    buildMetadataJson: metadata,
    image: {
      Id: configDigest,
      RepoTags: [KAFKA_TEST_IMAGE],
      Os: 'linux',
      Architecture: 'amd64',
      Config: {
        User: 'appuser',
        Entrypoint: ['/__cacert_entrypoint.sh'],
        Cmd: ['/etc/kafka/docker/run'],
        Labels: {
          'org.opencontainers.image.version': '4.4.0',
          'org.opencontainers.image.base.name': 'eclipse-temurin:25.0.5.1_2-jre-alpine-3.24',
          'org.opencontainers.image.base.digest': 'sha256:69c2a26e9894016e274c880fa542956d005b3755f84db765145185e87405bd89',
          'io.fdp.kafka.distribution.sha512': 'f5ceba8a265ab6bb2d68362b1f6859a95e15d60ef92ba83b43fdf065b557dc18e98d6383c15b9977320e25b3a2c8b6f11decb17b0fa6c0f163423793a0eb95cb',
          'io.fdp.java.version': '25.0.5.1+2',
        },
      },
    },
    javaVersionOutput: [
      '    java.version = 25.0.5.1',
      '    java.runtime.version = 25.0.5.1+2-LTS',
      '    java.vendor = Eclipse Adoptium',
    ].join('\n'),
    kafkaVersionOutput: '4.4.0',
    compressionJarOutput: [
      '02d1ceb848d510fce228f66febee3254978df7d70d53da472a98eb3b5cf9caaf  /opt/kafka/libs/lz4-java-1.14.1.jar',
      '1c03ce77b3f2e926c25a952612e372404d80bc88c6c8052bede964804fabd732  /opt/kafka/libs/snappy-java-1.1.10.12.jar',
    ].join('\n'),
    dockerfileText,
    ...overrides,
  };
}

test('accepts the loaded pinned Kafka image only when Buildx binds its manifest to the Engine image ID', () => {
  const result = validateKafkaTestImageEvidence(validEvidence());

  assert.equal(result.imageIdDigest, configDigest);
  assert.equal(result.dockerImageIdKind, 'config-digest');
  assert.equal(result.buildxManifestDigest, manifestDigest);
  assert.equal(result.imagePlatform, 'linux/amd64');
  assert.match(result.dockerfileSha256, /^[a-f0-9]{64}$/);
});

test('rejects a Kafka image built for a different platform', () => {
  assert.throws(() => validateKafkaTestImageEvidence(validEvidence({
    image: { ...validEvidence().image, Architecture: 'arm64' },
  })), /platform must be linux\/amd64/);
});

test('rejects a tag that no longer resolves to the inspected Kafka image', () => {
  assert.throws(() => validateKafkaTestImageEvidence(validEvidence({
    image: { ...validEvidence().image, RepoTags: ['fdp-local/kafka:unreviewed'] },
  })), /tag does not resolve/);
});

test('rejects a loaded Engine image whose config is not the Buildx output config', () => {
  assert.throws(() => validateKafkaTestImageEvidence(validEvidence({
    image: { ...validEvidence().image, Id: `sha256:${'c'.repeat(64)}` },
  })), /not bound to the Buildx manifest and config metadata/);
});

test('accepts a containerd Engine manifest ID when its descriptor binds the Buildx config', () => {
  const image = validEvidence().image;
  const result = validateKafkaTestImageEvidence(validEvidence({
    image: {
      ...image,
      Id: manifestDigest,
      Descriptor: {
        digest: manifestDigest,
        mediaType: 'application/vnd.oci.image.manifest.v1+json',
        annotations: { 'config.digest': configDigest },
      },
      RepoDigests: [`fdp-local/kafka@${manifestDigest}`],
    },
  }));

  assert.equal(result.dockerImageIdKind, 'manifest-descriptor');
  assert.equal(result.imageIdDigest, manifestDigest);
});

test('rejects a containerd manifest ID when its descriptor does not bind the Buildx config', () => {
  const image = validEvidence().image;
  assert.throws(() => validateKafkaTestImageEvidence(validEvidence({
    image: {
      ...image,
      Id: manifestDigest,
      Descriptor: {
        digest: manifestDigest,
        mediaType: 'application/vnd.oci.image.manifest.v1+json',
        annotations: { 'config.digest': `sha256:${'c'.repeat(64)}` },
      },
    },
  })), /does not bind the Buildx image config/);
});

test('rejects an incorrect Kafka runtime or replaced compression jar', () => {
  assert.throws(() => validateKafkaTestImageEvidence(validEvidence({
    javaVersionOutput: 'java.version = 25.0.4',
  })), /pinned patched Temurin/);
  assert.throws(() => validateKafkaTestImageEvidence(validEvidence({
    compressionJarOutput: '0000000000000000000000000000000000000000000000000000000000000000  /opt/kafka/libs/lz4-java-1.14.1.jar',
  })), /compression jars do not match/);
});

test('rejects an unreviewed Dockerfile input even when the labels still claim the expected versions', () => {
  assert.throws(() => validateKafkaTestImageEvidence(validEvidence({
    dockerfileText: 'FROM apache/kafka:latest',
  })), /Dockerfile is missing reviewed build inputs/);
});
