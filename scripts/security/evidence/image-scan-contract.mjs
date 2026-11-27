const javaApplicationImages = new Set([
  'transaction-ingest-service',
  'transaction-simulator-service',
  'feature-enricher-service',
  'fraud-scoring-service',
  'alert-service',
  'audit-trust-authority',
  'model-promotion-policy-publisher',
]);

const historicalApplicationImages = new Map([
  ['fdp37-alert-service-image-security', 'alert-service'],
  ['fdp38-alert-service-fixture-image-security', 'alert-service'],
  ['fdp39-release-image-security', 'alert-service'],
  ['fdp39-fixture-image-security', 'alert-service'],
]);

export function expectedScanCategories({ logicalName, source }) {
  if (logicalName === 'dockerfile-syntax') return [];

  const imagePurpose = historicalApplicationImages.get(logicalName) ?? logicalName;
  const categories = ['os-pkgs'];
  if (source === 'buildx-loaded-image'
    && (imagePurpose === 'ml-inference-service' || javaApplicationImages.has(imagePurpose))) {
    categories.push('lang-pkgs');
  }
  return categories;
}
