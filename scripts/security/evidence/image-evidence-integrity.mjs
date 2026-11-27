import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { SECURITY_POLICY } from '../config/policy.mjs';

export function sha256File(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

export function decodeHashedManifest(encoded, description, expectedDigest, failures) {
  if (typeof encoded !== 'string' || encoded.length === 0) {
    failures.push(`${description} raw manifest bytes are missing.`);
    return null;
  }
  const bytes = Buffer.from(encoded, 'base64');
  if (bytes.toString('base64') !== encoded) {
    failures.push(`${description} raw manifest bytes are not canonical base64.`);
    return null;
  }
  const actualDigest = `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
  if (actualDigest !== expectedDigest) failures.push(`${description} raw manifest hash does not match its recorded digest.`);
  try {
    return JSON.parse(bytes.toString('utf8'));
  } catch (error) {
    failures.push(`${description} raw manifest is invalid JSON: ${error.message}`);
    return null;
  }
}

export function verifyCycloneDxDocument(sbom, description, failures) {
  const failUnless = (condition, message) => {
    if (!condition) failures.push(message);
  };
  failUnless(sbom.bomFormat === SECURITY_POLICY.formats.imageSbomBomFormat
    && typeof sbom.specVersion === 'string'
    && /^1\.[0-9]+$/.test(sbom.specVersion)
    && Number.isInteger(sbom.version)
    && sbom.version > 0
    && Array.isArray(sbom.components)
    && sbom.components.length > 0
    && sbom.metadata && typeof sbom.metadata === 'object',
  `${description}: image SBOM is not a populated CycloneDX JSON document.`);
  failUnless(typeof sbom.serialNumber === 'string' && /^urn:uuid:[0-9a-f-]{36}$/i.test(sbom.serialNumber),
    `${description}: CycloneDX serialNumber is missing or malformed.`);
  failUnless(typeof sbom.metadata?.timestamp === 'string' && Number.isFinite(Date.parse(sbom.metadata.timestamp)),
    `${description}: CycloneDX metadata timestamp is missing or malformed.`);
  const tools = sbom.metadata?.tools?.components;
  failUnless(Array.isArray(tools) && tools.some((tool) => tool?.name === 'syft' && typeof tool.version === 'string'),
    `${description}: CycloneDX metadata does not identify the Syft generator.`);
  const bomRefs = new Set();
  for (const component of Array.isArray(sbom.components) ? sbom.components : []) {
    failUnless(component && typeof component.name === 'string' && component.name.length > 0
      && typeof component.type === 'string' && component.type.length > 0
      && typeof component.version === 'string' && component.version.length > 0
      && typeof component.purl === 'string' && component.purl.startsWith(SECURITY_POLICY.formats.packageUrlPrefix)
      && typeof component['bom-ref'] === 'string' && component['bom-ref'].length > 0,
    `${description}: image SBOM has an incomplete package component identity.`);
    if (typeof component?.['bom-ref'] !== 'string') continue;
    if (bomRefs.has(component['bom-ref'])) failures.push(`${description}: image SBOM contains a duplicate bom-ref.`);
    bomRefs.add(component['bom-ref']);
  }
}
