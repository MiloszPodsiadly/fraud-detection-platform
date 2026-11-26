#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SECURITY_POLICY } from '../config/policy.mjs';
import { IMAGE_SECURITY_INVENTORY, imageEntry } from '../config/image-inventory.mjs';

const KAFKA_RECIPE = IMAGE_SECURITY_INVENTORY.recipes.kafka;
const KAFKA_JAVA_IMAGE = imageEntry('infrastructure', 'temurin-kafka-runtime')?.imageRef;
const KAFKA_JAVA_VERSION = KAFKA_RECIPE.javaVersion;
export const KAFKA_TEST_IMAGE = imageEntry('infrastructure', 'apache-kafka-runtime')?.imageRef;
const IMAGE_PLATFORM = SECURITY_POLICY.platforms.image;
const DIGEST_PATTERN = /^sha256:[a-f0-9]{64}$/;
const MANIFEST_MEDIA_TYPES = new Set(SECURITY_POLICY.imageIdentity.manifestMediaTypes);
const KAFKA_ARCHIVE_SHA512 = KAFKA_RECIPE.distributionSha512;
const EXPECTED_RECIPE_INPUTS = [
  `FROM ${KAFKA_RECIPE.dockerAssetsImage} AS kafka-docker-assets`,
  `FROM ${KAFKA_JAVA_IMAGE}`,
  KAFKA_RECIPE.distributionUrl,
  KAFKA_ARCHIVE_SHA512,
  ...KAFKA_RECIPE.compressionJars.flatMap((jar) => [`${jar.name}-${jar.version}.jar`, jar.sha256]),
];

export function validateKafkaTestImageEvidence({
  imageRef,
  buildDigest,
  buildMetadataJson,
  image,
  javaVersionOutput,
  kafkaVersionOutput,
  compressionJarOutput,
  dockerfileText,
}) {
  if (imageRef !== KAFKA_TEST_IMAGE) {
    throw new Error(`Unexpected Kafka test image reference: ${imageRef || '<missing>'}`);
  }
  const buildMetadata = parseBuildxMetadata(buildMetadataJson);
  const buildIdentity = verifyLoadedImageIdentity(buildMetadata, buildDigest, image);
  if (!Array.isArray(image?.RepoTags) || !image.RepoTags.includes(KAFKA_TEST_IMAGE)) {
    throw new Error('The expected Kafka image tag does not resolve to the inspected Docker image.');
  }
  if (`${image.Os}/${image.Architecture}` !== IMAGE_PLATFORM) {
    throw new Error(`Kafka test image platform must be ${IMAGE_PLATFORM}; got ${image.Os}/${image.Architecture}.`);
  }
  if (image.Config?.User !== 'appuser'
    || JSON.stringify(image.Config?.Entrypoint) !== JSON.stringify(['/__cacert_entrypoint.sh'])
    || JSON.stringify(image.Config?.Cmd) !== JSON.stringify(['/etc/kafka/docker/run'])) {
    throw new Error('Kafka test image must retain its reviewed non-root user and upstream launch flow.');
  }

  const labels = image.Config?.Labels ?? {};
  const expectedLabels = {
    'org.opencontainers.image.version': KAFKA_RECIPE.version,
    'org.opencontainers.image.base.name': KAFKA_JAVA_IMAGE?.split('@')[0],
    'org.opencontainers.image.base.digest': `sha256:${KAFKA_JAVA_IMAGE?.split('@sha256:')[1]}`,
    'io.fdp.kafka.distribution.sha512': KAFKA_ARCHIVE_SHA512,
    'io.fdp.java.version': KAFKA_JAVA_VERSION,
  };
  for (const [key, expected] of Object.entries(expectedLabels)) {
    if (labels[key] !== expected) {
      throw new Error(`Kafka image label ${key} does not match the reviewed input.`);
    }
  }

  if (!javaVersionOutput.includes(`java.version = ${KAFKA_JAVA_VERSION.split('+')[0]}`)
    || !new RegExp(`^\\s*java\\.runtime\\.version = ${escapeRegExp(KAFKA_JAVA_VERSION)}(?:-LTS)?\\s*$`, 'm').test(javaVersionOutput)
    || !javaVersionOutput.includes('java.vendor = Eclipse Adoptium')) {
    throw new Error(`The loaded Kafka image does not run the pinned patched Temurin ${KAFKA_JAVA_VERSION} runtime.`);
  }
  if (!kafkaVersionOutput.includes(KAFKA_RECIPE.version)) {
    throw new Error(`The loaded Kafka image does not execute Kafka ${KAFKA_RECIPE.version}.`);
  }
  if (KAFKA_RECIPE.compressionJars.some((jar) => !compressionJarOutput.includes(
    `${jar.sha256}  /opt/kafka/libs/${jar.name}-${jar.version}.jar`))
    || KAFKA_RECIPE.compressionJars.some((jar) => compressionJarOutput.includes(jar.replaced))) {
    throw new Error('The loaded Kafka image compression jars do not match their reviewed versions and SHA-256 values.');
  }
  const missingRecipeInputs = EXPECTED_RECIPE_INPUTS.filter((input) => !dockerfileText.includes(input));
  if (missingRecipeInputs.length > 0) {
    throw new Error(`Kafka Dockerfile is missing reviewed build inputs: ${missingRecipeInputs.join(', ')}`);
  }

  return {
    imageRef,
    imagePlatform: `${image.Os}/${image.Architecture}`,
    imageIdDigest: image.Id,
    dockerImageIdKind: buildIdentity.dockerImageIdKind,
    buildxManifestDigest: buildIdentity.manifestDigest,
    buildxConfigDigest: buildIdentity.configDigest,
    buildxMediaType: buildIdentity.mediaType,
    buildxMetadataSha256: createHash('sha256').update(buildMetadataJson).digest('hex'),
    dockerfileSha256: createHash('sha256').update(dockerfileText).digest('hex'),
    kafkaVersion: KAFKA_RECIPE.version,
    javaVersion: KAFKA_JAVA_VERSION,
    kafkaArchiveSha512: KAFKA_ARCHIVE_SHA512,
    compressionJars: Object.fromEntries(KAFKA_RECIPE.compressionJars.map((jar) => [
      jar.name,
      { version: jar.version, sha256: jar.sha256 },
    ])),
    dockerUser: image.Config.User,
  };
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function parseBuildxMetadata(metadataJson) {
  let metadata;
  try {
    metadata = JSON.parse(metadataJson);
  } catch {
    throw new Error('Buildx metadata is not valid JSON.');
  }
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) {
    throw new Error('Buildx metadata must be an object.');
  }
  const manifestDigest = metadata['containerimage.digest'];
  const configDigest = metadata['containerimage.config.digest'];
  const descriptor = metadata['containerimage.descriptor'];
  if (!DIGEST_PATTERN.test(manifestDigest ?? '') || !DIGEST_PATTERN.test(configDigest ?? '')) {
    throw new Error('Buildx metadata must identify one platform manifest and its image config.');
  }
  if (!descriptor || descriptor.digest !== manifestDigest || !MANIFEST_MEDIA_TYPES.has(descriptor.mediaType)) {
    throw new Error('Buildx descriptor does not identify the image manifest.');
  }
  const descriptorConfigDigest = descriptor.annotations?.['config.digest'];
  if (descriptorConfigDigest && descriptorConfigDigest !== configDigest) {
    throw new Error('Buildx descriptor config digest disagrees with its image config metadata.');
  }
  if (descriptor.platform
    && `${descriptor.platform.os}/${descriptor.platform.architecture}` !== IMAGE_PLATFORM) {
    throw new Error(`Buildx metadata does not describe the required ${IMAGE_PLATFORM} platform.`);
  }
  return metadata;
}

function verifyLoadedImageIdentity(metadata, actionDigest, image) {
  const manifestDigest = metadata['containerimage.digest'];
  const configDigest = metadata['containerimage.config.digest'];
  const buildDescriptor = metadata['containerimage.descriptor'];
  if (!DIGEST_PATTERN.test(actionDigest ?? '') || actionDigest !== manifestDigest) {
    throw new Error('Buildx action digest output disagrees with its manifest metadata.');
  }
  if (!DIGEST_PATTERN.test(image?.Id ?? '')) {
    throw new Error('Docker Engine did not report a content-addressed Kafka image ID.');
  }

  const engineDescriptor = image.Descriptor;
  if (engineDescriptor) {
    if (engineDescriptor.digest !== manifestDigest
      || engineDescriptor.mediaType !== buildDescriptor.mediaType) {
      throw new Error('Docker Engine descriptor does not match the Buildx image manifest.');
    }
    const engineConfigDigest = engineDescriptor.annotations?.['config.digest'];
    if (engineConfigDigest && engineConfigDigest !== configDigest) {
      throw new Error('Docker Engine descriptor does not bind the Buildx image config digest.');
    }
  }

  let dockerImageIdKind;
  if (image.Id === configDigest) {
    dockerImageIdKind = 'config-digest';
  } else if (image.Id === manifestDigest
    && engineDescriptor?.digest === manifestDigest
    && engineDescriptor.annotations?.['config.digest'] === configDigest) {
    dockerImageIdKind = 'manifest-descriptor';
  } else {
    throw new Error('Loaded Docker image ID is not bound to the Buildx manifest and config metadata.');
  }

  const repoDigests = image.RepoDigests ?? [];
  if (repoDigests.length > 0 && !repoDigests.includes(`fdp-local/kafka@${manifestDigest}`)
    && !repoDigests.includes(`docker.io/fdp-local/kafka@${manifestDigest}`)) {
    throw new Error('Docker Engine repository digest does not match the Buildx output manifest.');
  }
  return {
    manifestDigest,
    configDigest,
    mediaType: buildDescriptor.mediaType,
    dockerImageIdKind,
  };
}

function capture(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 5 * 1024 * 1024 });
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  if (result.error || result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} failed: ${(result.error?.message ?? output).trim()}`);
  }
  return output;
}

function main() {
  const imageRef = process.env.KAFKA_IMAGE_REF ?? '';
  const buildDigest = process.env.KAFKA_BUILD_DIGEST ?? '';
  const buildMetadataJson = process.env.KAFKA_BUILD_METADATA_JSON ?? '';
  const dockerfilePath = path.resolve('deployment/Dockerfile.kafka');
  const dockerfileText = readFileSync(dockerfilePath, 'utf8');
  const imageInspectJson = capture('docker', ['image', 'inspect', imageRef]);
  const inspected = JSON.parse(imageInspectJson);
  if (!Array.isArray(inspected) || inspected.length !== 1) {
    throw new Error('Docker inspect did not return exactly one Kafka image.');
  }
  const javaVersionOutput = capture('docker', [
    'run', '--rm', '--platform', IMAGE_PLATFORM, '--entrypoint', 'java', imageRef,
    '-XshowSettings:properties', '-version',
  ]);
  const kafkaVersionOutput = capture('docker', [
    'run', '--rm', '--platform', IMAGE_PLATFORM, '--entrypoint', '/opt/kafka/bin/kafka-topics.sh', imageRef,
    '--version',
  ]);
  const compressionJarOutput = capture('docker', [
    'run', '--rm', '--platform', IMAGE_PLATFORM, '--entrypoint', 'sh', imageRef,
    '-ec',
    'set -- /opt/kafka/libs/lz4-java-*.jar; test "$#" -eq 1; sha256sum "$1"; set -- /opt/kafka/libs/snappy-java-*.jar; test "$#" -eq 1; sha256sum "$1"',
  ]);
  const verified = validateKafkaTestImageEvidence({
    imageRef,
    buildDigest,
    buildMetadataJson,
    image: inspected[0],
    javaVersionOutput,
    kafkaVersionOutput,
    compressionJarOutput,
    dockerfileText,
  });
  const repoPath = process.cwd().replaceAll('\\', '/');
  const testedSha = capture('git', ['-c', `safe.directory=${repoPath}`, 'rev-parse', 'HEAD']).trim();
  const expectedSha = process.env.GITHUB_SHA;
  if (expectedSha && testedSha !== expectedSha) {
    throw new Error(`Checked-out revision ${testedSha} differs from GitHub tested SHA ${expectedSha}.`);
  }
  const evidence = {
    schemaVersion: 1,
    testedGitSha: testedSha,
    workflow: process.env.GITHUB_WORKFLOW ?? null,
    job: process.env.GITHUB_JOB ?? null,
    runId: process.env.GITHUB_RUN_ID ?? null,
    buildxActionDigest: buildDigest,
    ...verified,
  };
  const outputPath = path.resolve('target/kafka-test-image-evidence.json');
  mkdirSync(path.dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, `${JSON.stringify(evidence, null, 2)}\n`);
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(process.env.GITHUB_OUTPUT,
      `image-id-digest=${verified.imageIdDigest}\nbuildx-manifest-digest=${verified.buildxManifestDigest}\nrecipe-sha256=${verified.dockerfileSha256}\n`);
  }
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(process.env.GITHUB_STEP_SUMMARY,
      `## Patched Kafka Test Image\n\n- tested SHA: \`${testedSha}\`\n- image: \`${verified.imageRef}\`\n- Buildx manifest digest: \`${verified.buildxManifestDigest}\`\n- loaded Docker image ID: \`${verified.imageIdDigest}\`\n- platform: \`${verified.imagePlatform}\`\n- Dockerfile SHA-256: \`${verified.dockerfileSha256}\`\n- Kafka: ${verified.kafkaVersion}\n- Temurin: ${verified.javaVersion}\n- compression jar SHA-256: lz4 \`${LZ4_SHA256}\`, snappy \`${SNAPPY_SHA256}\`\n- runtime user: \`${verified.dockerUser}\`\n`);
  }
  process.stdout.write(`${JSON.stringify(evidence, null, 2)}\n`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
