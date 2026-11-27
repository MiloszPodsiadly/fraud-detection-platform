import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { SECURITY_POLICY } from "../config/policy.mjs";
import { expectedScanCategories } from "./image-scan-contract.mjs";
import { discoverImageSourceReferences } from "./discover-image-source-references.mjs";
import {
  IMAGE_SECURITY_INVENTORY,
  IMAGE_SECURITY_INVENTORY_RELATIVE_PATH,
  imageEntry,
} from "../config/image-inventory.mjs";

const digestPattern = /^[^\s@]+(?:\/[^\s@]+)*@sha256:[a-f0-9]{64}$/;
const scanInventory = IMAGE_SECURITY_INVENTORY;
const scanInventoryPath = IMAGE_SECURITY_INVENTORY_RELATIVE_PATH;
const inventoryKafka = scanInventory.recipes.kafka;
const inventoryNodeBuilder = scanInventory.recipes.nodeBuilder;
const localKafkaImage = imageEntry("infrastructure", "apache-kafka-runtime")?.imageRef;
const localNodeBuilderImage = imageEntry("services", "node-builder")?.imageRef;
const nodeBuilderBaseImage = inventoryNodeBuilder?.baseImage;
const kafkaDockerAssetsImage = inventoryKafka?.dockerAssetsImage;
const buildkitBuilderImage = scanInventory.recipes.buildkitBuilderImage;
const kafkaDistributionSha512 = inventoryKafka?.distributionSha512;
const kafkaCompressionJars = inventoryKafka?.compressionJars ?? [];
if (![localKafkaImage, localNodeBuilderImage, nodeBuilderBaseImage, kafkaDockerAssetsImage, buildkitBuilderImage]
  .every((value) => typeof value === "string" && value.length > 0)
  || !/^[a-f0-9]{128}$/.test(kafkaDistributionSha512 ?? "")) {
  throw new Error(`${scanInventoryPath}: image recipes or release integrity values are incomplete.`);
}
const { sourceImages, unresolvedImageReferences, dynamicContainerReferences, dockerfiles, composeFiles } = discoverImageSourceReferences();
const failures = [];

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

const probeScript = "scripts/security/config/check-compose-bind-mount-security.sh";
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
  throw new Error(`Every Docker Buildx workflow must use the inventory-pinned BuildKit ${buildkitBuilderImage}`);
}
addReference(workflowPath, buildkitBuilderImage);
function jobSection(source, jobName) {
  const normalizedSource = source.replace(/\r\n/g, "\n");
  const header = "\n  " + jobName + ":\n";
  const start = normalizedSource.indexOf(header);
  if (start < 0) throw new Error("Required workflow job is missing: " + jobName);
  const bodyStart = start + header.length;
  const remainder = normalizedSource.slice(bodyStart);
  const nextJob = remainder.search(/\n  [a-z0-9_-]+:\n/);
  return remainder.slice(0, nextJob < 0 ? undefined : nextJob);
}

const githubShaExpression = String.fromCharCode(36) + "{{ github.sha }}";
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
if (scanInventory.recipes?.buildkitBuilderImage !== buildkitBuilderImage) {
  failures.push(`${scanInventoryPath}: BuildKit builder pin differs from the workflow's reviewed digest`);
}
if (!/^\d+\.\d+\.\d+$/.test(inventoryKafka?.version ?? "")
  || !inventoryKafka?.distributionUrl?.startsWith(`https://downloads.apache.org/kafka/${inventoryKafka.version}/`)
  || kafkaCompressionJars.length === 0
  || kafkaCompressionJars.some((jar) => !/^[a-z0-9-]+$/.test(jar?.name ?? "")
    || !/^\d+(?:\.\d+){2,3}$/.test(jar.version ?? "")
    || !/^https:\/\/repo\.maven\.apache\.org\/maven2\//.test(jar.url ?? "")
    || !/^[a-f0-9]{64}$/.test(jar.sha256 ?? "") || typeof jar.replaced !== "string" || jar.replaced.length === 0)) {
  failures.push(`${scanInventoryPath}: Kafka recipe must contain versioned official distribution and hashed Maven compression artifacts`);
}
if (!nodeBuilderBaseImage.startsWith(`node:${SECURITY_POLICY.tools.node}-`)
  || inventoryNodeBuilder?.npmVersion !== SECURITY_POLICY.tools.npm
  || !/^[a-f0-9]{64}$/.test(inventoryNodeBuilder?.npmTarballSha256 ?? "")
  || !inventoryNodeBuilder?.fixedPackages || typeof inventoryNodeBuilder.fixedPackages !== "object"
  || Object.keys(inventoryNodeBuilder.fixedPackages).length === 0) {
  failures.push(`${scanInventoryPath}: Node builder recipe does not match the pinned Node/npm toolchain or hash-locked package fixes`);
}
const kafkaBuildActionPath = '.github/actions/build-kafka-test-image/action.yml';
const kafkaBuildAction = fs.readFileSync(kafkaBuildActionPath, 'utf8');
const kafkaBuildTags = [...kafkaBuildAction.matchAll(/^\s+tags:\s+(\S+)\s*$/gm)].map((match) => match[1]);
if (kafkaBuildTags.length !== 1 || kafkaBuildTags[0] !== localKafkaImage
  || !kafkaBuildAction.includes(`KAFKA_IMAGE_REF: ${localKafkaImage}`)) {
  failures.push(`${kafkaBuildActionPath}: locally built Kafka tag must match the scanned image inventory`);
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
const buildAndScanActionPath = '.github/actions/build-and-scan-image/action.yml';
const buildAndScanAction = fs.readFileSync(buildAndScanActionPath, 'utf8');
const localBuildStep = buildAndScanAction.match(/\bid: build-image\b[\s\S]*?(?=\n    - name:)/)?.[0] ?? '';
if (!localBuildStep.includes('uses: docker/build-push-action@')
  || !/^\s+provenance:\s+false\s*$/m.test(localBuildStep)
  || !/^\s+outputs:\s+type=oci,dest=/m.test(localBuildStep)
  || !/^\s+push:\s+false\s*$/m.test(localBuildStep)) {
  failures.push(`${buildAndScanActionPath}: local scan builds must export a content-addressed OCI artifact without publishing or attaching a provenance index`);
}
if (!buildAndScanAction.includes('docker load --input "$OCI_ARCHIVE_PATH"')) {
  failures.push(`${buildAndScanActionPath}: the exact exported OCI artifact must be loaded before image scanning`);
}
const kafkaScanJob = jobSection(workflow, 'infrastructure-images');
if (!kafkaScanJob.includes("if: matrix.image.logicalName == 'apache-kafka-runtime'")
  || !kafkaScanJob.includes('uses: ./.github/actions/load-kafka-test-image')
  || !kafkaScanJob.includes('uses: ./.github/actions/scan-built-image')
  || !kafkaScanJob.includes('build-metadata-path: target/kafka-shared-image/buildx-metadata.json')
  || !kafkaScanJob.includes('oci-archive-path: target/kafka-shared-image/image.oci.tar')) {
  failures.push('infrastructure-images must scan the exact shared Kafka artifact with its producer metadata and OCI archive');
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
    expectedPlatform: SECURITY_POLICY.platforms.image,
    source: expectedSource,
    expectedScanCategories: expectedScanCategories({ logicalName, source: expectedSource }),
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
const kafkaArtifactUrl = inventoryKafka.distributionUrl;
if (
  !kafkaDockerfile.includes(`wget -O /tmp/kafka.tgz ${kafkaArtifactUrl}`)
  || !kafkaDockerfile.includes(`echo '${kafkaDistributionSha512}  /tmp/kafka.tgz' | sha512sum -c -`)
) {
  failures.push(`${kafkaDockerfilePath}: expected the exact official Kafka ${inventoryKafka.version} artifact and SHA-512 checksum`);
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
const npmTarballSha256 = inventoryNodeBuilder.npmTarballSha256;
const nodeBuilderEntry = imageEntries.find((entry) => entry["image-ref"] === localNodeBuilderImage);
if (nodeBuilderEntry?.source !== "buildx"
  || nodeBuilderEntry?.dockerfile !== frontendDockerfilePath
  || nodeBuilderEntry?.target !== "node-builder") {
  failures.push("The frontend Node build stage must have its own required image scan entry");
}
if (!frontendDockerfile.includes(`FROM ${nodeBuilderBaseImage} AS node-builder`)
  || !frontendDockerfile.includes(`ADD --checksum=sha256:${npmTarballSha256} https://registry.npmjs.org/npm/-/npm-${SECURITY_POLICY.tools.npm}.tgz /npm-${SECURITY_POLICY.tools.npm}.tgz`)
  || !frontendDockerfile.includes(`test "$(npm --version)" = "${SECURITY_POLICY.tools.npm}"`)
  || Object.entries(inventoryNodeBuilder.fixedPackages).some(([name, version]) =>
    !frontendDockerfile.includes(`"${name}", "${version}"`))) {
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
    expectedPlatform: SECURITY_POLICY.platforms.image,
    source: 'buildx-loaded-image',
    jobName: invocation.jobName,
    expectedScanCategories: expectedScanCategories({
      logicalName: artifactName,
      source: 'buildx-loaded-image',
    }),
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
