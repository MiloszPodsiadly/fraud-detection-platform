import { existsSync, lstatSync } from 'node:fs';
import { isAbsolute, relative, resolve, sep } from 'node:path';

export const NPM_SBOM_TOOLCHAIN_DIRECTORY = 'scripts/security/sbom/npm-sbom-tools';
export const NPM_SBOM_TOOLCHAIN_PACKAGE_NAME = 'fdp-sbom-toolchain';

export function resolveNpmSbomAuditPaths({ repositoryRoot, outputPath, packageDirectory }) {
  const root = resolve(repositoryRoot);
  if (!outputPath || !packageDirectory) {
    throw new Error('npm SBOM audit requires an explicit output path and package directory.');
  }
  const expectedPackageDirectory = resolve(root, ...NPM_SBOM_TOOLCHAIN_DIRECTORY.split('/'));
  const requestedPackageDirectory = isAbsolute(packageDirectory)
    ? resolve(packageDirectory)
    : resolve(root, packageDirectory);
  if (requestedPackageDirectory !== expectedPackageDirectory) {
    throw new Error('npm SBOM audit package directory must be the locked scripts/security/sbom/npm-sbom-tools workspace.');
  }
  if (!isDirectory(requestedPackageDirectory)) {
    throw new Error('npm SBOM audit package directory is missing or is not a regular directory.');
  }

  const packageJsonPath = resolve(requestedPackageDirectory, 'package.json');
  const lockfilePath = resolve(requestedPackageDirectory, 'package-lock.json');
  if (!isRegularFile(packageJsonPath) || !isRegularFile(lockfilePath)) {
    throw new Error('npm SBOM audit requires the package manifest and lockfile in the verified toolchain directory.');
  }
  const output = isAbsolute(outputPath) ? resolve(outputPath) : resolve(root, outputPath);
  const expectedOutputDirectory = resolve(root, 'target', 'sbom');
  const outputRelative = relative(expectedOutputDirectory, output);
  if (!outputRelative || outputRelative === '..' || outputRelative.startsWith(`..${sep}`) || isAbsolute(outputRelative)) {
    throw new Error('npm SBOM audit output must be a file under the repository target/sbom directory.');
  }
  if (outputRelative.split(sep).length !== 1 || !outputRelative.endsWith('.json')) {
    throw new Error('npm SBOM audit output must be a JSON file directly under target/sbom.');
  }
  return { repositoryRoot: root, packageDirectory: requestedPackageDirectory, packageJsonPath, lockfilePath, outputPath: output };
}

function isDirectory(filePath) {
  try {
    return lstatSync(filePath).isDirectory();
  } catch {
    return false;
  }
}

function isRegularFile(filePath) {
  try {
    return lstatSync(filePath).isFile();
  } catch {
    return false;
  }
}
