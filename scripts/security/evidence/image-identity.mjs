const DIGEST_PATTERN = /^sha256:[a-f0-9]{64}$/;

import { SECURITY_POLICY } from '../config/policy.mjs';

export const IMAGE_MANIFEST_MEDIA_TYPES = new Set([
  ...SECURITY_POLICY.imageIdentity.manifestMediaTypes,
]);
export const IMAGE_INDEX_MEDIA_TYPES = new Set([
  ...SECURITY_POLICY.imageIdentity.indexMediaTypes,
]);

export function parseDockerImageInspect(imageInspectJson, expectedPlatform) {
  const image = parseEngineInspect(imageInspectJson, expectedPlatform);
  const allowedImageFields = new Set(['Id', 'Os', 'Architecture', 'RepoTags', 'RepoDigests', 'Descriptor']);
  if (Object.keys(image).some((key) => !allowedImageFields.has(key))) {
    throw new Error('Docker Engine identity evidence contains fields outside the sanitized identity allowlist.');
  }
  validateDescriptorProjection(image.Descriptor);
  validateReferences(image);
  return image;
}

export function sanitizeDockerImageInspect(imageInspectJson, expectedPlatform) {
  const image = parseEngineInspect(imageInspectJson, expectedPlatform);
  validateReferences(image);
  const projected = {
    Id: image.Id,
    Os: image.Os,
    Architecture: image.Architecture,
    RepoTags: image.RepoTags ?? [],
    RepoDigests: image.RepoDigests ?? [],
  };
  if (image.Descriptor !== undefined) {
    const descriptor = image.Descriptor;
    if (!descriptor || typeof descriptor !== 'object' || Array.isArray(descriptor)
      || typeof descriptor.mediaType !== 'string' || !isSha256Digest(descriptor.digest)
      || (descriptor.size !== undefined && (!Number.isInteger(descriptor.size) || descriptor.size < 0))) {
      throw new Error('Docker Engine descriptor has a malformed identity projection.');
    }
    const annotations = descriptor.annotations;
    if (annotations !== undefined && (!annotations || typeof annotations !== 'object' || Array.isArray(annotations)
      || (annotations['config.digest'] !== undefined && !isSha256Digest(annotations['config.digest'])))) {
      throw new Error('Docker Engine descriptor annotations have a malformed identity projection.');
    }
    projected.Descriptor = {
      mediaType: descriptor.mediaType,
      digest: descriptor.digest,
      ...(descriptor.size !== undefined ? { size: descriptor.size } : {}),
      ...(annotations?.['config.digest'] ? { annotations: { 'config.digest': annotations['config.digest'] } } : {}),
    };
  }
  return `${JSON.stringify([projected], null, 2)}\n`;
}

function parseEngineInspect(imageInspectJson, expectedPlatform) {
  let images;
  try {
    images = JSON.parse(imageInspectJson);
  } catch {
    throw new Error('Docker Engine image inspect output is not valid JSON.');
  }
  if (!Array.isArray(images) || images.length !== 1 || !images[0] || typeof images[0] !== 'object') {
    throw new Error('Docker Engine must return exactly one inspected image.');
  }
  const image = images[0];
  if (!DIGEST_PATTERN.test(image.Id ?? '')) {
    throw new Error('Docker Engine image ID is not a valid content digest.');
  }
  const platform = `${image.Os}/${image.Architecture}`;
  if (platform !== expectedPlatform) {
    throw new Error(`Docker Engine image platform ${platform} does not match ${expectedPlatform}.`);
  }
  return image;
}

function validateReferences(image) {
  if (image.RepoTags !== undefined
    && (!Array.isArray(image.RepoTags) || image.RepoTags.some((value) => typeof value !== 'string'))) {
    throw new Error('Docker RepoTags must be an array of strings when present.');
  }
  if (image.RepoDigests !== undefined
    && (!Array.isArray(image.RepoDigests) || image.RepoDigests.some((value) => typeof value !== 'string'))) {
    throw new Error('Docker RepoDigests must be an array of strings when present.');
  }
}

function validateDescriptorProjection(descriptor) {
  if (descriptor === undefined) return;
  const allowedDescriptorFields = new Set(['mediaType', 'digest', 'size', 'annotations']);
  if (!descriptor || typeof descriptor !== 'object' || Array.isArray(descriptor)
    || Object.keys(descriptor).some((key) => !allowedDescriptorFields.has(key))
    || typeof descriptor.mediaType !== 'string' || !isSha256Digest(descriptor.digest)
    || (descriptor.size !== undefined && (!Number.isInteger(descriptor.size) || descriptor.size < 0))) {
    throw new Error('Docker Engine descriptor identity evidence is malformed or contains unreviewed fields.');
  }
  if (descriptor.annotations !== undefined
    && (!descriptor.annotations || typeof descriptor.annotations !== 'object' || Array.isArray(descriptor.annotations)
      || Object.keys(descriptor.annotations).some((key) => key !== 'config.digest')
      || (descriptor.annotations['config.digest'] !== undefined && !isSha256Digest(descriptor.annotations['config.digest'])))) {
    throw new Error('Docker Engine descriptor annotations contain unreviewed or malformed fields.');
  }
}

export function verifyImageEngineBinding({
  image,
  expectedPlatform,
  expectedRef,
  manifestDigest,
  manifestMediaType,
  configDigest,
  sourceDigest = manifestDigest,
  sourceMediaType = manifestMediaType,
  acceptedRepoDigests = [manifestDigest],
  requireRepoDigest = false,
}) {
  if (!image || typeof image !== 'object') throw new Error('Docker Engine image inspect record is missing.');
  if (!DIGEST_PATTERN.test(manifestDigest ?? '') || !DIGEST_PATTERN.test(configDigest ?? '')) {
    throw new Error('Image manifest and config digests must both be valid SHA-256 digests.');
  }
  if (!IMAGE_MANIFEST_MEDIA_TYPES.has(manifestMediaType)) {
    throw new Error(`Unsupported platform image manifest media type ${String(manifestMediaType)}.`);
  }
  if (!IMAGE_MANIFEST_MEDIA_TYPES.has(sourceMediaType) && !IMAGE_INDEX_MEDIA_TYPES.has(sourceMediaType)) {
    throw new Error(`Unsupported source image manifest media type ${String(sourceMediaType)}.`);
  }
  if (!isSha256Digest(sourceDigest)) throw new Error('Source image digest is invalid.');
  const platform = `${image.Os}/${image.Architecture}`;
  if (platform !== expectedPlatform) {
    throw new Error(`Docker Engine image platform ${platform} does not match ${expectedPlatform}.`);
  }
  if (!DIGEST_PATTERN.test(image.Id ?? '')) {
    throw new Error('Docker Engine image ID is not a valid content digest.');
  }

  const descriptor = image.Descriptor;
  let descriptorMatchesPlatform = false;
  let descriptorMatchesSource = false;
  if (descriptor) {
    descriptorMatchesPlatform = descriptor.digest === manifestDigest && descriptor.mediaType === manifestMediaType;
    descriptorMatchesSource = descriptor.digest === sourceDigest && descriptor.mediaType === sourceMediaType;
    if (!descriptorMatchesPlatform && !descriptorMatchesSource) {
      throw new Error('Docker Engine descriptor does not match the independently verified source or platform manifest.');
    }
    const descriptorConfigDigest = descriptor.annotations?.['config.digest'];
    if (descriptorConfigDigest && descriptorConfigDigest !== configDigest) {
      throw new Error('Docker Engine descriptor config digest does not match the platform manifest config.');
    }
  }

  let dockerEngineIdKind;
  if (image.Id === configDigest) {
    dockerEngineIdKind = 'config-digest';
  } else if (image.Id === manifestDigest && descriptorMatchesPlatform
    && (descriptor.annotations?.['config.digest'] === undefined
      || descriptor.annotations['config.digest'] === configDigest)) {
    dockerEngineIdKind = 'manifest-descriptor';
  } else if (image.Id === sourceDigest && sourceDigest !== manifestDigest
    && IMAGE_INDEX_MEDIA_TYPES.has(sourceMediaType) && descriptorMatchesSource) {
    dockerEngineIdKind = 'source-index-descriptor';
  } else {
    throw new Error('Docker Engine ID is not cryptographically bound to the verified source index or platform manifest and config.');
  }

  const repoDigests = Array.isArray(image.RepoDigests) ? image.RepoDigests : [];
  if (image.RepoDigests !== undefined && !Array.isArray(image.RepoDigests)) {
    throw new Error('Docker RepoDigests must be an array when present.');
  }
  if (image.RepoTags !== undefined && !Array.isArray(image.RepoTags)) {
    throw new Error('Docker RepoTags must be an array when present.');
  }
  const allowedDigests = new Set(acceptedRepoDigests.filter((digest) => DIGEST_PATTERN.test(digest ?? '')));
  const repoDigestMatches = repoDigests.some((repoDigest) => {
    const at = typeof repoDigest === 'string' ? repoDigest.lastIndexOf('@') : -1;
    return at > 0 && allowedDigests.has(repoDigest.slice(at + 1))
      && normalizeRepository(repoDigest.slice(0, at)) === normalizeRepository(repositoryOf(expectedRef));
  });
  if ((requireRepoDigest || repoDigests.length > 0) && !repoDigestMatches) {
    throw new Error('Docker RepoDigests do not bind the inspected image to the verified source or platform manifest.');
  }

  if (expectedRef.includes('@')) {
    const expectedDigest = expectedRef.slice(expectedRef.lastIndexOf('@') + 1);
    if (!allowedDigests.has(expectedDigest)
      || !repoDigests.some((repoDigest) => repoDigestMatchesReference(repoDigest, expectedRef, allowedDigests))) {
      throw new Error('Docker RepoDigests do not resolve the requested immutable image reference.');
    }
  } else {
    const repoTags = Array.isArray(image.RepoTags) ? image.RepoTags : [];
    if (!repoTags.some((repoTag) => normalizeReference(repoTag) === normalizeReference(expectedRef))) {
      throw new Error('The requested local image tag does not resolve to the inspected Docker Engine image.');
    }
  }

  return {
    dockerEngineId: image.Id,
    dockerEngineIdKind,
    dockerEngineConfigDigest: configDigest,
    dockerEngineDescriptorDigest: descriptor?.digest ?? null,
    dockerEngineDescriptorMediaType: descriptor?.mediaType ?? null,
    dockerEngineDescriptorConfigDigest: descriptor?.annotations?.['config.digest'] ?? null,
    repoDigests,
    imagePlatform: platform,
  };
}

export function isSha256Digest(value) {
  return typeof value === 'string' && DIGEST_PATTERN.test(value);
}

function repositoryOf(reference) {
  const at = reference.lastIndexOf('@');
  const value = at >= 0 ? reference.slice(0, at) : reference;
  const slash = value.lastIndexOf('/');
  const colon = value.lastIndexOf(':');
  return colon > slash ? value.slice(0, colon) : value;
}

function normalizeRepository(repository) {
  const normalized = repository.replace(/^(?:docker\.io|index\.docker\.io)\//, '');
  return normalized.includes('/') ? normalized : `library/${normalized}`;
}

function normalizeReference(reference) {
  const at = reference.lastIndexOf('@');
  if (at >= 0) return `${normalizeRepository(reference.slice(0, at))}@${reference.slice(at + 1)}`;
  const slash = reference.lastIndexOf('/');
  const colon = reference.lastIndexOf(':');
  const repository = colon > slash ? reference.slice(0, colon) : reference;
  const tag = colon > slash ? reference.slice(colon + 1) : null;
  const normalizedRepository = repository.includes('/') ? repository : `library/${repository}`;
  return tag === null ? normalizedRepository : `${normalizedRepository}:${tag}`;
}

function repoDigestMatchesReference(repoDigest, reference, allowedDigests) {
  const at = typeof repoDigest === 'string' ? repoDigest.lastIndexOf('@') : -1;
  return at > 0
    && allowedDigests.has(repoDigest.slice(at + 1))
    && normalizeRepository(repoDigest.slice(0, at)) === normalizeRepository(repositoryOf(reference));
}
