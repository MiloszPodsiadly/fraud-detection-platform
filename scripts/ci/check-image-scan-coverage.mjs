import fs from "node:fs";
import path from "node:path";

const digestPattern = /^[^\s@]+(?:\/[^\s@]+)*@sha256:[a-f0-9]{64}$/;
const sourceImages = new Map();

function addReference(file, ref) {
  const normalized = ref.trim().replace(/^['"]|['"]$/g, "");
  if (!normalized || normalized.startsWith("$")) return;
  const origins = sourceImages.get(normalized) ?? [];
  origins.push(file);
  sourceImages.set(normalized, origins);
}

function collectMatches(file, content, patterns) {
  for (const pattern of patterns) {
    for (const match of content.matchAll(pattern)) addReference(file, match[1]);
  }
}

function walkJavaFiles(directory, visit) {
  if (!fs.existsSync(directory)) return;
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) walkJavaFiles(entryPath, visit);
    else if (entry.isFile() && entry.name.endsWith(".java")) visit(entryPath);
  }
}

for (const filename of fs.readdirSync("deployment")) {
  const file = path.join("deployment", filename);
  if (!fs.statSync(file).isFile()) continue;
  const content = fs.readFileSync(file, "utf8");
  if (filename.startsWith("Dockerfile")) {
    collectMatches(file, content, [
      /^\s*#\s*syntax=(\S+)/gm,
      /^\s*FROM\s+(?:--platform=\S+\s+)?(\S+)/gm
    ]);
  }
  if (/^docker-compose.*\.ya?ml$/.test(filename)) {
    collectMatches(file, content, [/^\s+image:\s+(\S+)/gm]);
  }
}

for (const moduleName of fs.readdirSync(".", { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && !entry.name.startsWith("."))
  .map((entry) => entry.name)) {
  walkJavaFiles(path.join(moduleName, "src", "test", "java"), (file) => {
    const content = fs.readFileSync(file, "utf8");
    collectMatches(file, content, [
      /DockerImageName\.parse\(\s*"([^"]+)"/g,
      /new\s+(?:GenericContainer|MongoDBContainer|KafkaContainer)\s*(?:<[^>]*>)?\s*\(\s*"([^"]+)"/g
    ]);
  });
}

walkJavaFiles(path.join("common-test-support", "src", "main", "java"), (file) => {
  const content = fs.readFileSync(file, "utf8");
  collectMatches(file, content, [
    /DockerImageName\.parse\(\s*"([^"]+)"/g,
    /new\s+(?:GenericContainer|MongoDBContainer|KafkaContainer)\s*(?:<[^>]*>)?\s*\(\s*"([^"]+)"/g
  ]);
});

const probeScript = "scripts/ci/check-compose-bind-mount-security.sh";
const probeContent = fs.readFileSync(probeScript, "utf8");
collectMatches(probeScript, probeContent, [
  /^probe_image="([^"]+)"/gm,
  /^\s+image:\s+(\S+)/gm
]);

const pom = fs.readFileSync("pom.xml", "utf8");
collectMatches("pom.xml", pom, [
  /<testcontainers\.(?:ryuk|tinyimage)\.image>([^<]+)<\/testcontainers\.(?:ryuk|tinyimage)\.image>/g
]);

const workflowPath = ".github/workflows/docker-compose-ci.yml";
const workflow = fs.readFileSync(workflowPath, "utf8");
const externalImageJob = workflow.split("\n  external-images:")[1];
if (!externalImageJob) throw new Error("external-images scan matrix is missing");
const scannedImages = new Set();
for (const match of externalImageJob.matchAll(/^\s+image:\s+(\S+)/gm)) {
  const image = match[1];
  if (!digestPattern.test(image)) throw new Error(`scan matrix contains an unpinned image: ${image}`);
  scannedImages.add(image.slice(image.indexOf("@") + 1));
}

const failures = [];
for (const [image, origins] of sourceImages) {
  if (!digestPattern.test(image)) {
    failures.push(`${origins.join(", ")}: image reference is not pinned: ${image}`);
    continue;
  }
  const digest = image.slice(image.indexOf("@") + 1);
  if (!scannedImages.has(digest)) {
    failures.push(`${origins.join(", ")}: image digest is missing from external image scan matrix: ${image}`);
  }
}

if (failures.length) {
  console.error("Container image scan coverage failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`Verified ${sourceImages.size} unique pinned source image references against ${scannedImages.size} scanned digests.`);
