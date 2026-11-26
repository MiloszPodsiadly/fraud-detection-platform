export function verifyNpmSbomToolchainEvidence(packageJson, lockfile, auditReport, source = 'npm SBOM toolchain') {
  const directAjvVersion = packageJson?.dependencies?.ajv;
  if (directAjvVersion !== '8.20.0'
    || lockfile?.packages?.['']?.dependencies?.ajv !== directAjvVersion) {
    throw new Error(`${source} must directly pin AJV 8.20.0 in its package manifest and lockfile.`);
  }

  const ajvPackages = Object.entries(lockfile.packages ?? {})
    .filter(([path, record]) => path.split('node_modules/').at(-1) === 'ajv' || record?.name === 'ajv');
  if (ajvPackages.length === 0 || ajvPackages.some(([, record]) => !isPatchedAjvVersion(record.version))) {
    throw new Error(`${source} contains a missing or unpatched AJV installation.`);
  }

  const lockedPackageCount = Object.entries(lockfile.packages ?? {})
    .filter(([path, record]) => path !== '' && record?.link !== true).length;
  const vulnerabilities = auditReport?.metadata?.vulnerabilities;
  const categoryNames = ['info', 'low', 'moderate', 'high', 'critical'];
  if (auditReport?.auditReportVersion !== 2 || !auditReport.vulnerabilities
    || Object.keys(auditReport.vulnerabilities).length !== 0
    || !vulnerabilities || categoryNames.some((name) => !Number.isInteger(vulnerabilities[name]))
    || vulnerabilities.total !== categoryNames.reduce((sum, name) => sum + vulnerabilities[name], 0)
    || vulnerabilities.total !== 0
    || !Number.isInteger(auditReport.metadata?.dependencies?.total)
    || auditReport.metadata.dependencies.total !== lockedPackageCount) {
    throw new Error(`${source} audit report is missing, stale, incomplete, or contains vulnerabilities.`);
  }

  return {
    directAjvVersion,
    ajvInstallations: ajvPackages.map(([path, record]) => ({ path, version: record.version })),
    lockedPackageCount,
    vulnerabilities,
    auditReportVersion: auditReport.auditReportVersion,
  };
}

function isPatchedAjvVersion(version) {
  const match = /^(\d+)\.(\d+)\.(\d+)(?:\+[^-+]+)?$/.exec(version ?? '');
  return Boolean(match && (Number(match[1]) > 8 || (Number(match[1]) === 8 && Number(match[2]) >= 18)));
}
