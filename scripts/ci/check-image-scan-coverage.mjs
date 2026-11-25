import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";

const digestPattern = /^[^\s@]+(?:\/[^\s@]+)*@sha256:[a-f0-9]{64}$/;
const localKafkaImage = "fdp-local/kafka:4.4.0-temurin-25.0.5.1_2-r3";
const localNodeBuilderImage = "fdp-local/node-builder:26.13.1-npm12.2.3";
const nodeBuilderBaseImage = "node:26.13.1-alpine3.24@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5";
const kafkaDockerAssetsImage = "apache/kafka:4.4.0@sha256:833b867a9585a141da455dc2b57431c65cbccb27f11c91f694444cca9f777e0f";
const buildkitBuilderImage = "moby/buildkit:v0.35.1@sha256:baf6138eb8c37644800c7c913fba051e840ebb9ba13eaf92d92ef9a1712d0b4c";
const kafkaDistributionSha512 = "f5ceba8a265ab6bb2d68362b1f6859a95e15d60ef92ba83b43fdf065b557dc18e98d6383c15b9977320e25b3a2c8b6f11decb17b0fa6c0f163423793a0eb95cb";
const kafkaCompressionJars = [
  {
    name: "lz4-java",
    version: "1.14.1",
    url: "https://repo.maven.apache.org/maven2/at/yawk/lz4/lz4-java/1.14.1/lz4-java-1.14.1.jar",
    sha256: "02d1ceb848d510fce228f66febee3254978df7d70d53da472a98eb3b5cf9caaf",
    replaced: "lz4-java-1.11.2.jar",
  },
  {
    name: "snappy-java",
    version: "1.1.10.12",
    url: "https://repo.maven.apache.org/maven2/org/xerial/snappy/snappy-java/1.1.10.12/snappy-java-1.1.10.12.jar",
    sha256: "1c03ce77b3f2e926c25a952612e372404d80bc88c6c8052bede964804fabd732",
    replaced: "snappy-java-1.1.10.7.jar",
  },
];
const sourceImages = new Map();
const failures = [];
const unresolvedImageReferences = [];
const sharedJavaImageConstants = new Set();

function addReference(file, ref) {
  const normalizedFile = file.split(path.sep).join("/");
  const normalized = ref.trim().replace(/^['"]|['"]$/g, "").replace(/^docker:\/\//, "");
  if (!normalized || normalized === "scratch") return;
  if (normalizedFile === ".github/workflows/docker-compose-ci.yml" && normalized === "${{") return;
  if (normalized.includes("$") || normalized.includes("${")) {
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

function walkJavaFiles(directory, visit) {
  if (!fs.existsSync(directory)) return;
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) walkJavaFiles(entryPath, visit);
    else if (entry.isFile() && entry.name.endsWith(".java")) visit(entryPath);
  }
}

function collectJavaImageConstants(file, content) {
  const imageConstants = new Map();
  const pattern = /\b(?:(?:public|protected|private)\s+)?(?:static\s+)?(?:final\s+)?String\s+([A-Za-z_$][\w$]*_IMAGE|IMAGE|IMAGE_REF)\s*=\s*"([^"]+)"/g;
  for (const match of content.matchAll(pattern)) {
    imageConstants.set(match[1], match[2]);
    sharedJavaImageConstants.add(`${path.basename(file, ".java")}.${match[1]}`);
    addReference(file, match[2]);
  }
  return new Set(imageConstants.keys());
}

function walkFiles(directory, visit, output = []) {
  if (!fs.existsSync(directory)) return output;
  for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    if (entry.isDirectory() && [".git", "node_modules", "target", ".venv", ".venv-sbom-ml"].includes(entry.name)) continue;
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) walkFiles(entryPath, visit, output);
    else if (entry.isFile()) visit(entryPath);
  }
  return output;
}

const dockerfiles = [];
walkFiles(".", (file) => {
  if (!/^Dockerfile(?:$|[._-])/.test(path.basename(file)) && !/\.dockerfile$/i.test(file)) return;
  dockerfiles.push(file);
  const content = fs.readFileSync(file, "utf8");
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

const composeFiles = [];
walkFiles(".", (file) => {
  if (!/^(?:docker-compose|compose)(?:[._-].*)?\.ya?ml$/i.test(path.basename(file))) return;
  composeFiles.push(file);
  collectMatches(file, fs.readFileSync(file, "utf8"), [/^\s+image:\s+(\S+)/gm]);
});

const dynamicContainerReferences = [];

walkJavaFiles(path.join("common-test-support", "src", "main", "java"), (file) => {
  collectJavaImageConstants(file, fs.readFileSync(file, "utf8"));
});

for (const moduleName of fs.readdirSync(".", { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && !entry.name.startsWith("."))
  .map((entry) => entry.name)) {
  walkJavaFiles(path.join(moduleName, "src", "test", "java"), (file) => {
    if (/ArchitectureTest\.java$/i.test(path.basename(file))
      || path.basename(file) === "KafkaImageReferenceTest.java") return;
    const content = fs.readFileSync(file, "utf8");
    const literalImageConstants = collectJavaImageConstants(file, content);
    collectMatches(file, content, [
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
  });
}

walkJavaFiles(path.join("common-test-support", "src", "main", "java"), (file) => {
  const content = fs.readFileSync(file, "utf8");
  const literalImageConstants = collectJavaImageConstants(file, content);
  collectMatches(file, content, [
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
});

const probeScript = "scripts/ci/check-compose-bind-mount-security.sh";
const probeContent = fs.readFileSync(probeScript, "utf8");
const probeImage = probeContent.match(/^probe_image=["']([^"']+)["']\s*$/m)?.[1];
if (!probeImage) failures.push(`${probeScript}: the static probe image assignment is missing or unresolved`);
else addReference(probeScript, probeImage);
for (const match of probeContent.matchAll(/^\s+image:\s+(\S+)/gm)) {
  const reference = match[1].replace(/^['"]|['"]$/g, "");
  if (["$probe_image", "${probe_image}"].includes(reference) && probeImage) continue;
  addReference(probeScript, reference);
}

function collectDockerBuildTags(file, content) {
  const buildPattern = /\bdocker\s+(?:build|buildx\s+build)\b[\s\S]{0,512}?(?:--tag(?:=|\s+)|-t\s+)(?:"([^"]+)"|'([^']+)'|([^\s\\]+))/g;
  for (const match of content.matchAll(buildPattern)) {
    addReference(file, match[1] ?? match[2] ?? match[3]);
  }
}

walkFiles("scripts", (file) => {
  if (!/\.(?:sh|ps1)$/i.test(file)) return;
  collectDockerBuildTags(file, fs.readFileSync(file, "utf8"));
});
walkFiles(".github/actions", (file) => {
  if (!/\.ya?ml$/i.test(file)) return;
  collectDockerBuildTags(file, fs.readFileSync(file, "utf8"));
});

const pom = fs.readFileSync("pom.xml", "utf8");
collectMatches("pom.xml", pom, [
  /<testcontainers\.(?:ryuk|tinyimage)\.image>([^<]+)<\/testcontainers\.(?:ryuk|tinyimage)\.image>/g
]);

walkFiles(".github", (file) => {
  if (!/\.ya?ml$/i.test(file)) return;
  const content = fs.readFileSync(file, "utf8");
  const scanActionPath = ".github/actions/scan-built-image/action.yml";
  const normalizedFile = file.split(path.sep).join("/");
  let source = content;
  if (normalizedFile === scanActionPath) {
    source = source
      .replace(/^[ \t]+image:[ \t]+\$\{\{[ \t]*inputs\.image-ref[ \t]*\}\}[ \t]*\r?$/gm, "")
      .replace(/^[ \t]+image:[ \t]+docker:\$\{\{[ \t]*steps\.image-evidence\.outputs\.image-id-digest[ \t]*\}\}[ \t]*\r?$/gm, "");
  }
  if (normalizedFile === ".github/workflows/docker-compose-ci.yml") {
    source = source.split(/\r?\n/)
      .filter((line) => !/^[ \t]*image:[ \t]*\$\{\{[ \t]*fromJSON\(needs\.image-inventory\.outputs\.(?:backend|services|infrastructure)\)/.test(line))
      .join("\n");
  }
  source = source.split(/\r?\n/)
    .filter((line) => !line.trimStart().startsWith("image: ${{ fromJSON(needs.image-inventory.outputs."))
    .join("\n");
  collectMatches(file, source, [
    /^\s+image:\s+(\S+)/gm,
    /^\s+container:\s+(\S+)/gm,
    /^\s+uses:\s*docker:\/\/(\S+)/gm,
  ]);
});

const workflowPath = ".github/workflows/docker-compose-ci.yml";
const workflow = fs.readFileSync(workflowPath, "utf8");
const ciWorkflowPath = ".github/workflows/ci.yml";
const ciWorkflow = fs.readFileSync(ciWorkflowPath, "utf8");
const buildkitReferences = [workflow, ciWorkflow].flatMap((source) =>
  [...source.matchAll(/^\s+image=(moby\/buildkit:\S+)/gm)].map((match) => match[1])
);
const buildxSetupCount = [workflow, ciWorkflow].reduce((count, source) =>
  count + [...source.matchAll(/^\s+uses:\s*docker\/setup-buildx-action@/gm)].length, 0);
if (buildxSetupCount === 0 || buildkitReferences.length !== buildxSetupCount
  || buildkitReferences.some((image) => image !== buildkitBuilderImage)) {
  throw new Error("Every Docker Buildx workflow must use the same pinned BuildKit v0.35.1 image");
}
addReference(workflowPath, buildkitBuilderImage);
function jobSection(source, jobName) {
  const header = "\n  " + jobName + ":\n";
  const start = source.indexOf(header);
  if (start < 0) throw new Error("Required workflow job is missing: " + jobName);
  const bodyStart = start + header.length;
  const remainder = source.slice(bodyStart);
  const nextJob = remainder.search(/\n  [a-z0-9_-]+:\n/);
  return remainder.slice(0, nextJob < 0 ? undefined : nextJob);
}

const githubShaExpression = String.fromCharCode(36) + "{{ github.sha }}";
const scanInventoryPath = "scripts/ci/image-security-inventory.json";
let scanInventory;
try {
  scanInventory = JSON.parse(fs.readFileSync(scanInventoryPath, "utf8"));
} catch (error) {
  throw new Error(`${scanInventoryPath} is missing or invalid JSON: ${error.message}`);
}
const scanGroups = [
  { key: "backend", job: "backend-images", parallelism: 3 },
  { key: "services", job: "service-images", parallelism: 2 },
  { key: "infrastructure", job: "infrastructure-images", parallelism: 4 },
];
const dynamicMatrixImageReferences = [...workflow.matchAll(/^[ \t]*image:[ \t]*(\$\{\{[^\r\n]*\}\})[ \t]*\r?$/gm)]
  .map((match) => match[1]);
const expectedMatrixImageReferences = scanGroups.map(({ key }) =>
  `${String.fromCharCode(36)}{{ fromJSON(needs.image-inventory.outputs.${key}) }}`);
if (dynamicMatrixImageReferences.length !== expectedMatrixImageReferences.length
  || expectedMatrixImageReferences.some((reference) => !dynamicMatrixImageReferences.includes(reference))) {
  failures.push("docker-compose-ci.yml may only use the three reviewed inventory-derived image matrix references");
}
if (scanInventory.schemaVersion !== 1 || !scanInventory.groups || typeof scanInventory.groups !== "object") {
  failures.push(`${scanInventoryPath}: unsupported inventory schema or missing groups`);
}
const inventoryKafka = scanInventory.recipes?.kafka;
const inventoryNodeBuilder = scanInventory.recipes?.nodeBuilder;
const sameCompressionPins = JSON.stringify(inventoryKafka?.compressionJars) === JSON.stringify(kafkaCompressionJars);
if (scanInventory.recipes?.buildkitBuilderImage !== buildkitBuilderImage) {
  failures.push(`${scanInventoryPath}: BuildKit builder pin differs from the workflow's reviewed digest`);
}
if (inventoryKafka?.version !== "4.4.0"
  || inventoryKafka?.distributionUrl !== "https://downloads.apache.org/kafka/4.4.0/kafka_2.13-4.4.0.tgz"
  || inventoryKafka?.distributionSha512 !== kafkaDistributionSha512
  || inventoryKafka?.dockerAssetsImage !== kafkaDockerAssetsImage
  || !sameCompressionPins) {
  failures.push(`${scanInventoryPath}: Kafka recipe pins differ from the reviewed official release and patched compression artifacts`);
}
if (inventoryNodeBuilder?.baseImage !== nodeBuilderBaseImage
  || inventoryNodeBuilder?.npmVersion !== "12.2.3"
  || inventoryNodeBuilder?.npmTarballSha256 !== "fa0711dd513fa1386bbad3962a292cb813461cb57dedf07d6eb7efe76dffe897"
  || JSON.stringify(inventoryNodeBuilder?.fixedPackages) !== JSON.stringify({ "brace-expansion": "5.0.12", undici: "6.29.1" })) {
  failures.push(`${scanInventoryPath}: Node builder recipe pins differ from the reviewed patched runtime`);
}
const expectedJobs = scanGroups.map(({ job }) => job);
if (JSON.stringify(scanInventory.requiredScanJobs) !== JSON.stringify(expectedJobs)) {
  failures.push(`${scanInventoryPath}: requiredScanJobs must list the bounded backend, service, and infrastructure matrix jobs in order`);
}
const imageEntries = [];
for (const { key, job, parallelism } of scanGroups) {
  const entries = scanInventory.groups?.[key];
  if (!Array.isArray(entries) || entries.length === 0) {
    failures.push(`${scanInventoryPath}: group ${key} must contain at least one image`);
    continue;
  }
  for (const inventoryEntry of entries) {
    if (!inventoryEntry || typeof inventoryEntry !== "object" || !inventoryEntry.logicalName
      || !inventoryEntry.source || !inventoryEntry.artifactName) {
      failures.push(`${scanInventoryPath}: ${key} contains an incomplete image entry`);
      continue;
    }
    const imageRef = inventoryEntry.imageRef
      ?? (inventoryEntry.repository ? `${inventoryEntry.repository}:${githubShaExpression}` : "");
    imageEntries.push({
      source: inventoryEntry.source,
      "image-ref": imageRef,
      "artifact-name": inventoryEntry.artifactName,
      dockerfile: inventoryEntry.dockerfile,
      target: inventoryEntry.target,
      "build-args": inventoryEntry.buildArgs,
      _group: key,
      _logicalName: inventoryEntry.logicalName,
    });
  }

  const jobText = jobSection(workflow, job);
  const matrixExpression = `image: ${String.fromCharCode(36)}{{ fromJSON(needs.image-inventory.outputs.${key}) }}`;
  if (!jobText.includes(matrixExpression)) {
    failures.push(`${job}: matrix must be generated from the checked-in ${key} image inventory`);
  }
  if (!jobText.includes(`max-parallel: ${parallelism}`) || !jobText.includes("fail-fast: false")) {
    failures.push(`${job}: matrix parallelism must be bounded to ${parallelism} with fail-fast disabled for complete evidence`);
  }
  if (!jobText.includes("timeout-minutes: 120")) failures.push(`${job}: a 120-minute timeout is required`);
  if (!jobText.includes("uses: ./.github/actions/build-and-scan-image")) {
    failures.push(`${job}: the matrix does not invoke the reviewed build-and-scan action`);
  }
  if (jobText.includes("continue-on-error:")) {
    failures.push(`${job}: image build and scan failures must fail the matrix job`);
  }
}
const imageScanActionCount = [...workflow.matchAll(/^\s+uses:\s*\.\/\.github\/actions\/build-and-scan-image\s*$/gm)].length;
if (imageScanActionCount !== scanGroups.length) {
  failures.push("docker-compose-ci.yml must define exactly one build-and-scan step in each bounded image matrix");
}
const inventoryJob = jobSection(workflow, "image-inventory");
if (!inventoryJob.includes("--write-github-matrices") || !inventoryJob.includes("--write-inventory")) {
  failures.push("image-inventory must validate the checked-in inventory and publish its three matrix outputs");
}
const evidenceJob = jobSection(workflow, "image-scan-evidence");
if (!evidenceJob.includes("needs: [image-inventory, backend-images, service-images, infrastructure-images]")) {
  failures.push("image-scan-evidence must aggregate all matrix results and the inventory preparation job");
}
const requiredBackendModules = new Set([
  "transaction-ingest-service",
  "transaction-simulator-service",
  "feature-enricher-service",
  "fraud-scoring-service",
  "alert-service",
  "audit-trust-authority",
  "model-promotion-policy-publisher",
]);
const requiredServiceImages = new Set(["ml-inference-service", "analyst-console-ui"]);
const serviceDockerfiles = new Map([
  ["ml-inference-service", "deployment/Dockerfile.ml-inference"],
  ["analyst-console-ui", "deployment/Dockerfile.frontend"],
]);
const scannedImages = new Set();
const expectedArtifacts = [];
const sha = process.env.TESTED_GIT_SHA ?? process.env.GITHUB_SHA ?? "";
if ((process.argv.includes("--write-inventory") || process.argv.includes("--write-ci-inventory"))
  && !/^[a-f0-9]{40}$/.test(sha)) {
  throw new Error("TESTED_GIT_SHA or GITHUB_SHA must contain the 40-character revision for an inventory artifact.");
}
if (process.argv.includes("--write-inventory") || process.argv.includes("--write-ci-inventory")) {
  const checkoutSha = execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).trim();
  if (checkoutSha !== sha) throw new Error("Checked out revision " + checkoutSha + " does not match inventory SHA " + sha + ".");
}

const backendNames = new Set();
const serviceNames = new Set();
const externalNames = new Set();
const seenArtifactNames = new Set();
for (const entry of imageEntries) {
  const artifactName = entry["artifact-name"];
  const imageRef = entry["image-ref"];
  const backendMatch = artifactName.match(/^backend-image-(.+)-security$/);
  const serviceMatch = artifactName.match(/^service-image-(.+)-security$/);
  const externalMatch = artifactName.match(/^external-image-(.+)-security$/);
  const matches = [backendMatch, serviceMatch, externalMatch].filter(Boolean);
  if (matches.length !== 1) {
    failures.push("image-security has an invalid scan artifact name: " + artifactName);
    continue;
  }
  const group = backendMatch ? "backend" : serviceMatch ? "service" : "external";
  const logicalName = matches[0][1];
  if (entry._logicalName !== logicalName) {
    failures.push(`${scanInventoryPath}: artifact ${artifactName} does not match logical image ${entry._logicalName}`);
  }
  if ((entry._group === "backend" && group !== "backend")
    || (entry._group === "infrastructure" && group !== "external")
    || (entry._group === "services" && group !== "service" && logicalName !== "node-builder")) {
    failures.push(`${scanInventoryPath}: artifact ${artifactName} is assigned to the wrong bounded matrix group`);
  }
  if (seenArtifactNames.has(artifactName)) failures.push("image-security contains a duplicate scan artifact: " + artifactName);
  seenArtifactNames.add(artifactName);

  let expectedSource;
  if (group === "backend") {
    if (backendNames.has(logicalName)) failures.push("image-security has a duplicate backend image: " + logicalName);
    backendNames.add(logicalName);
    const expectedImage = "fdp-local/" + logicalName + ":" + githubShaExpression;
    if (!requiredBackendModules.has(logicalName)) failures.push("image-security contains an unexpected backend application image " + logicalName);
    if (entry.source !== "buildx" || imageRef !== expectedImage
      || entry.dockerfile !== "deployment/Dockerfile.backend"
      || entry["build-args"] !== "MODULE_NAME=" + logicalName || entry.target) {
      failures.push("Backend image " + logicalName + " must use the pinned Buildx Dockerfile and module build argument.");
    }
    expectedSource = "buildx-loaded-image";
  } else if (group === "service") {
    if (serviceNames.has(logicalName)) failures.push("image-security has a duplicate service image: " + logicalName);
    serviceNames.add(logicalName);
    const expectedImage = "fdp-local/" + logicalName + ":" + githubShaExpression;
    if (!requiredServiceImages.has(logicalName)) failures.push("image-security contains an unexpected service image " + logicalName);
    if (entry.source !== "buildx" || imageRef !== expectedImage
      || entry.dockerfile !== serviceDockerfiles.get(logicalName)
      || entry["build-args"] || entry.target) {
      failures.push("Service image " + logicalName + " must use its reviewed Buildx Dockerfile.");
    }
    expectedSource = "buildx-loaded-image";
  } else {
    if (externalNames.has(logicalName)) failures.push("image-security has a duplicate external logical image name: " + logicalName);
    externalNames.add(logicalName);
    if (imageRef === localKafkaImage) {
      if (logicalName !== "apache-kafka-runtime" || entry.source !== "buildx"
        || entry.dockerfile !== "deployment/Dockerfile.kafka" || entry.target || entry["build-args"]) {
        failures.push("Apache Kafka custom image must be built from its dedicated Dockerfile and scanned as a Buildx-loaded image.");
      }
      scannedImages.add(imageRef);
      expectedSource = "buildx-loaded-image";
    } else if (imageRef === localNodeBuilderImage) {
      if (logicalName !== "node-builder" || entry.source !== "buildx"
        || entry.dockerfile !== "deployment/Dockerfile.frontend" || entry.target !== "node-builder" || entry["build-args"]) {
        failures.push("Node builder scan must build the node-builder target from deployment/Dockerfile.frontend.");
      }
      scannedImages.add(imageRef);
      expectedSource = "buildx-loaded-image";
    } else {
      if (entry.source !== "registry" || !digestPattern.test(imageRef)
        || entry.dockerfile || entry.target || entry["build-args"]) {
        failures.push("External registry image " + logicalName + " must be pulled by immutable digest without build inputs.");
      }
      if (digestPattern.test(imageRef)) scannedImages.add(imageRef.slice(imageRef.indexOf("@") + 1));
      expectedSource = "registry-pinned";
    }
  }

  expectedArtifacts.push({
    artifactName: artifactName + "-" + sha,
    logicalName,
    imageRef,
    expectedPlatform: "linux/amd64",
    source: expectedSource,
  });
}
for (const moduleName of requiredBackendModules) {
  if (!backendNames.has(moduleName)) failures.push("image-security is missing required application image " + moduleName);
}
for (const moduleName of backendNames) {
  if (!requiredBackendModules.has(moduleName)) failures.push("image-security contains an unexpected application image " + moduleName);
}
for (const serviceName of requiredServiceImages) {
  if (!serviceNames.has(serviceName)) failures.push("image-security is missing required application image " + serviceName);
}
for (const serviceName of serviceNames) {
  if (!requiredServiceImages.has(serviceName)) failures.push("image-security contains an unexpected service image " + serviceName);
}
const kafkaDockerfilePath = "deployment/Dockerfile.kafka";
const kafkaDockerfileLines = fs.readFileSync(kafkaDockerfilePath, "utf8").split(/\r?\n/);
const kafkaComposePath = "deployment/docker-compose.yml";
const kafkaCompose = fs.readFileSync(kafkaComposePath, "utf8");
const composeKafkaImages = [...kafkaCompose.matchAll(/^\s+image:\s+(fdp-local\/kafka:\S+)\s*$/gm)]
  .map((match) => match[1]);
const composeClusterIds = [...kafkaCompose.matchAll(/^\s+CLUSTER_ID:\s*(\S+)\s*$/gm)].map((match) => match[1]);
const composeKafkaLogDirectories = [...kafkaCompose.matchAll(/^\s+KAFKA_LOG_DIRS:\s*(\S+)\s*$/gm)]
  .map((match) => match[1]);
const composeKafkaVolumeMounts = [...kafkaCompose.matchAll(/^\s+- kafka-data:(\S+)\s*$/gm)]
  .map((match) => match[1]);
const brokerComposeBlock = kafkaCompose.match(/^  kafka:\r?\n([\s\S]*?)(?=^  [a-z0-9_-]+:\r?\n|^volumes:\r?\n)/m)?.[1] ?? "";
const topicInitializerComposeBlock = kafkaCompose.match(/^  kafka-topics-init:\r?\n([\s\S]*?)(?=^  [a-z0-9_-]+:\r?\n|^volumes:\r?\n)/m)?.[1] ?? "";
const topLevelComposeVolumes = kafkaCompose.match(/^volumes:\s*\n([\s\S]*)$/m)?.[1] ?? "";
if (composeKafkaImages.length !== 2 || composeKafkaImages.some((image) => image !== localKafkaImage)) {
  failures.push(`${kafkaComposePath}: broker and topic initializer must use the same reviewed local Kafka image`);
}
if (!/^    pull_policy:\s*build\s*$/m.test(brokerComposeBlock)
  || !/^    pull_policy:\s*never\s*$/m.test(topicInitializerComposeBlock)) {
  failures.push(`${kafkaComposePath}: Kafka broker must build locally and topic initialization must never pull a substitute image`);
}
if (composeClusterIds.length !== 1 || composeClusterIds[0] !== "4L6g3nShT-eMCtK--X86sw") {
  failures.push(`${kafkaComposePath}: Kafka must define one stable KRaft cluster ID`);
}
if (composeKafkaLogDirectories.length !== 1 || composeKafkaLogDirectories[0] !== "/var/lib/kafka/data"
  || composeKafkaVolumeMounts.length !== 1 || composeKafkaVolumeMounts[0] !== "/var/lib/kafka/data"
  || !brokerComposeBlock.includes("- kafka-data:/var/lib/kafka/data")
  || !/^  kafka-data:\s*$/m.test(topLevelComposeVolumes)) {
  failures.push(`${kafkaComposePath}: Kafka log and KRaft metadata must share the single declared kafka-data volume`);
}
const kafkaSourceDeclaration = `FROM ${kafkaDockerAssetsImage} AS kafka-docker-assets`;
const kafkaSourceIndexes = kafkaDockerfileLines.flatMap((line, index) =>
  line.trim() === kafkaSourceDeclaration ? [index] : []
);
if (kafkaSourceIndexes.length !== 1) {
  failures.push(`${kafkaDockerfilePath}: expected one pinned Kafka Docker assets stage`);
} else {
  const sourceStart = kafkaSourceIndexes[0];
  const nextStage = kafkaDockerfileLines.findIndex(
    (line, index) => index > sourceStart && /^\s*FROM\s/.test(line)
  );
  const sourceInstructions = kafkaDockerfileLines
    .slice(sourceStart + 1, nextStage < 0 ? undefined : nextStage)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("#"));
  if (nextStage < 0 || sourceInstructions.length > 0) {
    failures.push(`${kafkaDockerfilePath}: Kafka Docker assets stage must contain only its pinned FROM`);
  }
}

const kafkaDockerfile = kafkaDockerfileLines.join("\n");
const kafkaArtifactUrl = "https://downloads.apache.org/kafka/4.4.0/kafka_2.13-4.4.0.tgz";
if (
  !kafkaDockerfile.includes(`wget -O /tmp/kafka.tgz ${kafkaArtifactUrl}`)
  || !kafkaDockerfile.includes(`echo '${kafkaDistributionSha512}  /tmp/kafka.tgz' | sha512sum -c -`)
) {
  failures.push(`${kafkaDockerfilePath}: expected the exact official Kafka 4.4.0 release artifact and SHA-512 checksum`);
}
for (const dependency of kafkaCompressionJars) {
  const jarPath = `/tmp/${dependency.name}-${dependency.version}.jar`;
  if (!kafkaDockerfile.includes(`wget -O ${jarPath} ${dependency.url}`)
    || !kafkaDockerfile.includes(`echo '${dependency.sha256}  ${jarPath}' | sha256sum -c -`)
    || !kafkaDockerfile.includes(`install -o appuser -g appuser -m 0644 ${jarPath} /opt/kafka/libs/${dependency.name}-${dependency.version}.jar`)
    || !kafkaDockerfile.includes(`test -f /opt/kafka/libs/${dependency.replaced}`)
    || !kafkaDockerfile.includes(`rm /opt/kafka/libs/${kafkaCompressionJars[0].replaced} /opt/kafka/libs/${kafkaCompressionJars[1].replaced}`)) {
    failures.push(`${kafkaDockerfilePath}: ${dependency.name} must replace the reviewed vulnerable Kafka JAR with the checksum-pinned ${dependency.version} artifact`);
  }
}

const kafkaAssetCopies = kafkaDockerfileLines.filter((line) => line.startsWith("COPY --from=kafka-docker-assets "));
if (kafkaAssetCopies.length !== 1 || kafkaAssetCopies[0] !== "COPY --from=kafka-docker-assets /__cacert_entrypoint.sh /__cacert_entrypoint.sh") {
  failures.push(`${kafkaDockerfilePath}: only the reviewed CA entrypoint may be copied from the official Kafka Docker image`);
}
if (!kafkaDockerfile.includes("source=/etc/kafka/docker,target=/mnt/kafka-docker,ro")
  || !kafkaDockerfile.includes("cp -a /mnt/kafka-docker/. /etc/kafka/docker/")) {
  failures.push(`${kafkaDockerfilePath}: the reviewed Kafka Docker launch scripts must remain the only mounted runtime assets`);
}

const frontendDockerfilePath = "deployment/Dockerfile.frontend";
const frontendDockerfile = fs.readFileSync(frontendDockerfilePath, "utf8");
const npmTarballSha256 = "fa0711dd513fa1386bbad3962a292cb813461cb57dedf07d6eb7efe76dffe897";
const nodeBuilderEntry = imageEntries.find((entry) => entry["image-ref"] === localNodeBuilderImage);
if (nodeBuilderEntry?.source !== "buildx"
  || nodeBuilderEntry?.dockerfile !== frontendDockerfilePath
  || nodeBuilderEntry?.target !== "node-builder") {
  failures.push("The frontend Node build stage must have its own required image scan entry");
}
if (!frontendDockerfile.includes(`FROM ${nodeBuilderBaseImage} AS node-builder`)
  || !frontendDockerfile.includes(`ADD --checksum=sha256:${npmTarballSha256} https://registry.npmjs.org/npm/-/npm-12.2.3.tgz /npm-12.2.3.tgz`)
  || !frontendDockerfile.includes('test "$(npm --version)" = "12.2.3"')
  || !frontendDockerfile.includes('["brace-expansion", "5.0.12"]')
  || !frontendDockerfile.includes('["undici", "6.29.1"]')) {
  failures.push(`${frontendDockerfilePath}: patched Node builder base, npm artifact checksum, or fixed npm dependencies are not pinned`);
}

for (const [image, origins] of sourceImages) {
  if (image === kafkaDockerAssetsImage) {
    if (origins.some((origin) => origin !== "deployment/Dockerfile.kafka")) {
      failures.push(`${origins.join(", ")}: build-only Kafka source image escaped its isolated Dockerfile stage`);
    }
    continue;
  }
  if (image === nodeBuilderBaseImage) {
    if (origins.some((origin) => origin !== frontendDockerfilePath) || !nodeBuilderEntry) {
      failures.push(`${origins.join(", ")}: Node base must be covered by the scanned patched node-builder target`);
    }
    continue;
  }
  if (image === localKafkaImage || image === localNodeBuilderImage) {
    if (!scannedImages.has(image)) {
      failures.push(`${origins.join(", ")}: locally built image is missing from the scan matrix: ${image}`);
    }
    continue;
  }
  if (!digestPattern.test(image)) {
    failures.push(`${origins.join(", ")}: image reference is not pinned: ${image}`);
    continue;
  }
  const digest = image.slice(image.indexOf("@") + 1);
  if (!scannedImages.has(digest)) {
    failures.push(`${origins.join(", ")}: image digest is missing from external image scan matrix: ${image}`);
  }
}

const ciDynamicImageSources = new Map([
  ["alert-service/src/test/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationProductionImageChaosHarness.java:imageName", {
    imageRef: "fdp37-alert-service:${{ github.sha }}",
    artifactName: "fdp37-alert-service-image-security",
  }],
  ["alert-service/src/test/java/com/frauddetection/alert/regulated/chaos/RegulatedMutationLiveCheckpointChaosHarness.java:fixtureImageName", {
    imageRef: "fdp38-alert-service-test-fixture:${{ github.sha }}",
    artifactName: "fdp38-alert-service-fixture-image-security",
  }],
]);
const resolvedDynamicImages = [];
for (const reference of dynamicContainerReferences) {
  const normalizedPath = reference.file.split(path.sep).join("/");
  const resolution = ciDynamicImageSources.get(`${normalizedPath}:${reference.argument}`);
  if (!resolution) {
    failures.push(`${normalizedPath}: unresolved Testcontainers image expression ${reference.argument}`);
    continue;
  }
  if (!ciWorkflow.includes(`image-ref: ${resolution.imageRef}`)
    || !ciWorkflow.includes(`artifact-name: ${resolution.artifactName}`)) {
    failures.push(`${normalizedPath}: dynamic image ${reference.argument} is not bound to its required CI scan artifact`);
    continue;
  }
  resolvedDynamicImages.push({ source: normalizedPath, imageArgument: reference.argument, ...resolution });
}

function collectBuiltImageScanInvocations(source) {
  const lines = source.split(/\r?\n/);
  const invocations = [];
  let jobName = null;
  for (let index = 0; index < lines.length; index += 1) {
    const jobHeader = lines[index].match(/^  ([a-z0-9_-]+):\s*$/);
    if (jobHeader) jobName = jobHeader[1];
    if (!lines[index].includes('uses: ./.github/actions/scan-built-image')) continue;

    const fields = {};
    for (let fieldIndex = index + 1; fieldIndex < lines.length && !/^      - (?:name|uses):/.test(lines[fieldIndex]); fieldIndex += 1) {
      const field = lines[fieldIndex].match(/^          (image-ref|artifact-name|build-digest|build-metadata):\s*(.*?)\s*$/);
      if (field) fields[field[1]] = field[2];
    }
    invocations.push({ jobName, ...fields });
  }
  return invocations;
}

const requiredCiImageScans = new Map([
  ['fdp37-alert-service-image-security', 'fdp37-alert-service:${{ github.sha }}'],
  ['fdp38-alert-service-fixture-image-security', 'fdp38-alert-service-test-fixture:${{ github.sha }}'],
  ['fdp39-release-image-security', 'fdp39-alert-service:${{ github.sha }}'],
  ['fdp39-fixture-image-security', 'fdp38-alert-service-test-fixture:${{ github.sha }}'],
]);
const ciImageScanInvocations = collectBuiltImageScanInvocations(ciWorkflow);
const ciImageArtifacts = [];
if (ciImageScanInvocations.length !== requiredCiImageScans.size) {
  failures.push(`ci.yml must contain exactly ${requiredCiImageScans.size} reviewed image scan invocations; found ${ciImageScanInvocations.length}`);
}
const seenCiArtifacts = new Set();
for (const invocation of ciImageScanInvocations) {
  const artifactName = invocation['artifact-name'];
  const expectedImageRef = requiredCiImageScans.get(artifactName);
  if (!expectedImageRef || seenCiArtifacts.has(artifactName)) {
    failures.push(`ci.yml contains an unexpected or duplicate built-image scan artifact: ${String(artifactName)}`);
    continue;
  }
  seenCiArtifacts.add(artifactName);
  if (invocation['image-ref'] !== expectedImageRef) {
    failures.push(`ci.yml scan artifact ${artifactName} targets ${String(invocation['image-ref'])}, expected ${expectedImageRef}`);
  }
  if (!/^\$\{\{ steps\.[a-z0-9_-]+\.outputs\.digest \}\}$/.test(invocation['build-digest'] ?? '')
    || !/^\$\{\{ steps\.[a-z0-9_-]+\.outputs\.metadata \}\}$/.test(invocation['build-metadata'] ?? '')) {
    failures.push(`ci.yml scan artifact ${artifactName} must receive both digest and metadata outputs from its image build`);
  }
  ciImageArtifacts.push({
    artifactName: `${artifactName}-${sha}`,
    logicalName: artifactName,
    imageRef: expectedImageRef,
    expectedPlatform: 'linux/amd64',
    source: 'buildx-loaded-image',
    jobName: invocation.jobName,
  });
}
for (const requiredName of requiredCiImageScans.keys()) {
  if (!seenCiArtifacts.has(requiredName)) failures.push(`ci.yml is missing required image scan artifact ${requiredName}`);
}

for (const reference of unresolvedImageReferences) {
  failures.push(`${reference.file}: unresolved dynamic image reference ${reference.ref}`);
}

if (failures.length) {
  console.error("Container image scan coverage failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

const inventoryArgument = process.argv.indexOf("--write-inventory");
const ciInventoryArgument = process.argv.indexOf("--write-ci-inventory");
const writeGitHubMatrices = process.argv.includes("--write-github-matrices");
if (inventoryArgument >= 0 && ciInventoryArgument >= 0) {
  throw new Error("Choose only one inventory output mode.");
}
if (writeGitHubMatrices && (inventoryArgument < 0 || ciInventoryArgument >= 0)) {
  throw new Error("--write-github-matrices requires --write-inventory.");
}
if (writeGitHubMatrices && !process.env.GITHUB_OUTPUT) {
  throw new Error("GITHUB_OUTPUT is required when writing matrix outputs.");
}
for (const [argument, mode] of [[inventoryArgument, "compose"], [ciInventoryArgument, "ci"]]) {
  if (argument < 0) continue;
  const inventoryPath = process.argv[argument + 1];
  if (!inventoryPath || inventoryPath.startsWith("--")) {
    throw new Error(`--write-${mode === "ci" ? "ci-" : ""}inventory must be followed by an output file path.`);
  }
  mkdirSync(path.dirname(inventoryPath), { recursive: true });
  writeFileSync(inventoryPath, `${JSON.stringify({
    schemaVersion: 1,
    testedGitSha: sha,
    requiredScanJobs: mode === "ci" ? ["fdp37", "fdp38", "fdp39"] : scanInventory.requiredScanJobs,
    expectedArtifacts: mode === "ci" ? ciImageArtifacts : expectedArtifacts,
    dynamicImageUses: resolvedDynamicImages,
    sourceImageReferences: [...sourceImages].map(([imageRef, origins]) => ({ imageRef, origins: [...new Set(origins)].sort() })),
    dockerfiles: dockerfiles.map((file) => file.split(path.sep).join("/")).sort(),
    composeFiles: composeFiles.map((file) => file.split(path.sep).join("/")).sort(),
  }, null, 2)}\n`);
}
if (writeGitHubMatrices) {
  const matrixOutputs = scanGroups.map(({ key }) => `${key}=${JSON.stringify(scanInventory.groups[key])}`).join("\n");
  fs.appendFileSync(process.env.GITHUB_OUTPUT, `${matrixOutputs}\n`, "utf8");
}

console.log(`Verified ${sourceImages.size} unique image references against ${scannedImages.size} scan matrix entries and ${expectedArtifacts.length} expected artifacts.`);
