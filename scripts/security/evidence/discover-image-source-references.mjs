import fs from 'node:fs';
import path from 'node:path';

export function discoverImageSourceReferences() {
  const sourceImages = new Map();
  const unresolvedImageReferences = [];
  const dynamicContainerReferences = [];
  const sharedJavaImageConstants = new Set();
  const dockerfiles = [];
  const composeFiles = [];

  function addReference(file, ref) {
    const normalizedFile = file.split(path.sep).join('/');
    const normalized = ref.trim().replace(/^['"]|['"]$/g, '').replace(/^docker:\/\//, '');
    if (!normalized || normalized === 'scratch') return;
    if (normalizedFile === '.github/workflows/docker-compose-ci.yml' && normalized === '${{') return;
    if (normalized.includes('$') || normalized.includes('${')) {
      unresolvedImageReferences.push({ file: normalizedFile, ref: normalized });
      return;
    }
    const origins = sourceImages.get(normalized) ?? [];
    origins.push(normalizedFile);
    sourceImages.set(normalized, origins);
  }

  function collectMatches(file, content, patterns) {
    for (const pattern of patterns) {
      for (const match of content.matchAll(pattern)) addReference(file, match[1]);
    }
  }

  function walkFiles(directory, visit) {
    if (!fs.existsSync(directory)) return;
    for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.isDirectory() && ['.git', 'node_modules', 'target', '.venv', '.venv-sbom-ml'].includes(entry.name)) continue;
      const entryPath = path.join(directory, entry.name);
      if (entry.isDirectory()) walkFiles(entryPath, visit);
      else if (entry.isFile()) visit(entryPath);
    }
  }

  function walkJavaFiles(directory, visit) {
    if (!fs.existsSync(directory)) return;
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const entryPath = path.join(directory, entry.name);
      if (entry.isDirectory()) walkJavaFiles(entryPath, visit);
      else if (entry.isFile() && entry.name.endsWith('.java')) visit(entryPath);
    }
  }

  function collectJavaImageConstants(file, content) {
    const imageConstants = new Map();
    const pattern = /\b(?:(?:public|protected|private)\s+)?(?:static\s+)?(?:final\s+)?String\s+((?:[A-Za-z_$][\w$]*(?:_IMAGE_REFERENCE|_IMAGE))|IMAGE|IMAGE_REF)\s*=\s*"([^"]+)"/g;
    for (const match of content.matchAll(pattern)) {
      imageConstants.set(match[1], match[2]);
      sharedJavaImageConstants.add(`${path.basename(file, '.java')}.${match[1]}`);
      addReference(file, match[2]);
    }
    return new Set(imageConstants.keys());
  }

  walkFiles('.', (file) => {
    if (!/^Dockerfile(?:$|[._-])/.test(path.basename(file)) && !/\.dockerfile$/i.test(file)) return;
    dockerfiles.push(file);
    const content = fs.readFileSync(file, 'utf8');
    collectMatches(file, content, [/^\s*#\s*syntax=(\S+)/gm]);
    const declaredStages = new Set();
    for (const line of content.split(/\r?\n/)) {
      const from = line.match(/^\s*FROM\s+(?:--platform=\S+\s+)?(\S+)(?:\s+AS\s+(\S+))?/i);
      if (!from) continue;
      const imageOrStage = from[1];
      if (!declaredStages.has(imageOrStage.toLowerCase())) addReference(file, imageOrStage);
      if (from[2]) declaredStages.add(from[2].toLowerCase());
    }
  });

  walkFiles('.', (file) => {
    if (!/^(?:docker-compose|compose)(?:[._-].*)?\.ya?ml$/i.test(path.basename(file))) return;
    composeFiles.push(file);
    collectMatches(file, fs.readFileSync(file, 'utf8'), [/^\s+image:\s+(\S+)/gm]);
  });

  const collectJavaContainers = (file, skipStaticArchitectureChecks = false) => {
    if (skipStaticArchitectureChecks
      && (/ArchitectureTest\.java$/i.test(path.basename(file)) || path.basename(file) === 'KafkaImageReferenceTest.java')) return;
    const content = fs.readFileSync(file, 'utf8');
    const containerImageCode = content.replace(/\.isCompatibleWith\(DockerImageName\.parse\(\s*"[^"]+"\s*\)\)/g, '');
    const literalImageConstants = collectJavaImageConstants(file, content);
    collectMatches(file, containerImageCode, [
      /DockerImageName\.parse\(\s*(?<!\\)"([^"\\]+)"/g,
      /new\s+(?:GenericContainer|MongoDBContainer|KafkaContainer)\s*(?:<[^>]*>)?\s*\(\s*(?<!\\)"([^"\\]+)"/g,
    ]);
    for (const match of content.matchAll(/DockerImageName\.parse\(\s*([^)]*)\)/g)) {
      const argument = match[1].trim();
      if (!argument.startsWith('"') && !argument.startsWith("'")
        && !literalImageConstants.has(argument) && !sharedJavaImageConstants.has(argument)) {
        dynamicContainerReferences.push({ file, argument });
      }
    }
  };

  const sharedJavaSource = path.join('common-test-support', 'src', 'main', 'java');
  walkJavaFiles(sharedJavaSource, (file) => collectJavaImageConstants(file, fs.readFileSync(file, 'utf8')));
  for (const moduleName of fs.readdirSync('.', { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && !entry.name.startsWith('.'))
    .map((entry) => entry.name)) {
    walkJavaFiles(path.join(moduleName, 'src', 'test', 'java'), (file) => collectJavaContainers(file, true));
  }
  walkJavaFiles(sharedJavaSource, (file) => collectJavaContainers(file));

  return { sourceImages, unresolvedImageReferences, dynamicContainerReferences, dockerfiles, composeFiles };
}
