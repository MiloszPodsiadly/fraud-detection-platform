import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { repoRoot } from "./scope-guards/scopeGuardHelpers.mjs";

const workflowPath = ".github/workflows/ci.yml";
const evidencePath = "docs/ci_evidence_map.md";
const planPath = "docs/ci_consolidation_plan.md";
const workflowsDirectory = ".github/workflows";

const workflow = readFileSync(join(repoRoot, workflowPath), "utf8");
const evidence = readFileSync(join(repoRoot, evidencePath), "utf8");
const plan = readOptional(planPath);
const workflowSources = new Map(readdirSync(join(repoRoot, workflowsDirectory))
  .filter((name) => name.endsWith(".yml") || name.endsWith(".yaml"))
  .map((name) => [`${workflowsDirectory}/${name}`, readFileSync(join(repoRoot, workflowsDirectory, name), "utf8")]));

const currentJobs = parseWorkflowJobs(workflow);
const evidenceSections = parseEvidenceSections(evidence);
const evidenceByName = new Map(evidenceSections.map((section) => [section.name, section]));
const crossWorkflowReferences = parseCrossWorkflowJobReferences(evidenceSections);
const invalidCrossWorkflowReferences = [];
const imageInventory = readImageInventory();
for (const reference of crossWorkflowReferences) {
  const source = workflowSources.get(reference.workflowPath);
  if (!source) {
    invalidCrossWorkflowReferences.push(`${reference.section}: workflow does not exist (${reference.workflowPath})`);
    continue;
  }
  const jobIds = new Set(parseWorkflowJobs(source).map((job) => job.id));
  if (!jobIds.has(reference.jobId)) {
    invalidCrossWorkflowReferences.push(`${reference.section}: job does not exist (${reference.workflowPath}::${reference.jobId})`);
    continue;
  }
  if (reference.matrixSelector) {
    const matchingGroups = Object.entries(imageInventory.groups ?? {})
      .filter(([, entries]) => Array.isArray(entries)
        && entries.some((entry) => entry.logicalName === reference.matrixSelector));
    if (matchingGroups.length !== 1) {
      invalidCrossWorkflowReferences.push(`${reference.section}: matrix entry is missing or duplicated (${reference.matrixSelector})`);
      continue;
    }
    const expectedJob = {
      backend: "backend-images",
      services: "service-images",
      infrastructure: "infrastructure-images",
    }[matchingGroups[0][0]];
    if (reference.workflowPath.endsWith("/docker-compose-ci.yml") && reference.jobId !== expectedJob) {
      invalidCrossWorkflowReferences.push(`${reference.section}: matrix entry ${reference.matrixSelector} belongs to ${expectedJob}, not ${reference.jobId}`);
    }
  }
}

const missingEvidence = currentJobs.filter((job) => !evidenceByName.has(job.name));
const staleEvidence = evidenceSections.filter((section) => {
  if (currentJobs.some((job) => job.name === section.name)) {
    return false;
  }
  if (crossWorkflowReferences.some((reference) => reference.section === section.name)) {
    return false;
  }
  return !/\bReplaced by:/i.test(section.body);
});
const consolidated = evidenceSections.filter((section) => /\bReplaced by:/i.test(section.body));
const missingReplacementMapping = consolidated.filter((section) => !/Replaced by:\s*\S/i.test(section.body));

console.log("Current CI jobs:");
for (const job of currentJobs) {
  console.log(`- ${job.name} (${job.id})`);
}

console.log("\nEvidence map entries:");
for (const section of evidenceSections) {
  console.log(`- ${section.name}`);
}

console.log("\nJobs with no evidence map entry:");
printList(missingEvidence.map((job) => `${job.name} (${job.id})`));

console.log("\nEvidence map entries with no matching current job:");
printList(staleEvidence.map((section) => section.name));

console.log("\nJobs marked as consolidated/replaced:");
printList(consolidated.map((section) => section.name));

console.log("\nCross-workflow evidence references:");
printList(crossWorkflowReferences.map((reference) =>
  `${reference.section}: ${reference.workflowPath}::${reference.jobId}${reference.matrixSelector ? `[${reference.matrixSelector}]` : ""}`));
console.log("\nInvalid cross-workflow evidence references:");
printList(invalidCrossWorkflowReferences);

if (plan) {
  const implemented = /Decision:\s*(?:\r?\n\s*-\s*)?implemented/i.test(plan);
  console.log(`\nConsolidation plan implemented marker: ${implemented ? "present" : "missing"}`);
}

if (missingEvidence.length > 0 || staleEvidence.length > 0 || missingReplacementMapping.length > 0
  || invalidCrossWorkflowReferences.length > 0) {
  console.error("\nCI evidence comparison failed");
  process.exit(1);
}

function parseWorkflowJobs(source) {
  const lines = source.split(/\r?\n/);
  const jobs = [];
  let inJobs = false;
  let current = null;
  for (const line of lines) {
    if (line === "jobs:") {
      inJobs = true;
      continue;
    }
    if (!inJobs) {
      continue;
    }
    const jobMatch = line.match(/^  ([A-Za-z0-9_-]+):$/);
    if (jobMatch) {
      current = { id: jobMatch[1], name: jobMatch[1] };
      jobs.push(current);
      continue;
    }
    const nameMatch = line.match(/^    name:\s*(.+)$/);
    if (current && nameMatch) {
      current.name = nameMatch[1].replace(/^["']|["']$/g, "");
    }
  }
  return jobs;
}

function parseEvidenceSections(source) {
  const matches = [...source.matchAll(/^## (.+)$/gm)];
  return matches.map((match, index) => {
    const start = match.index + match[0].length;
    const end = matches[index + 1]?.index ?? source.length;
    return {
      name: match[1].trim(),
      body: source.slice(start, end)
    };
  });
}

function parseCrossWorkflowJobReferences(sections) {
  const references = [];
  for (const section of sections) {
    for (const line of section.body.split(/\r?\n/)) {
      const bullet = line.match(/^\s*-\s*`([^`]+)`\s*$/);
      if (!bullet) continue;
      const match = bullet[1].match(/^(?<workflow>[^:]+\.ya?ml)::(?<job>[a-z0-9_-]+)(?:\[(?<matrix>[^\]]+)\])?$/);
      if (!match) continue;
      const workflow = match.groups.workflow.startsWith(".github/workflows/")
        ? match.groups.workflow
        : `${workflowsDirectory}/${match.groups.workflow}`;
      references.push({
        section: section.name,
        workflowPath: workflow,
        jobId: match.groups.job,
        matrixSelector: match.groups.matrix ?? "",
      });
    }
  }
  return references;
}

function readImageInventory() {
  try {
    return JSON.parse(readFileSync(join(repoRoot, "scripts/ci/image-security-inventory.json"), "utf8"));
  } catch {
    return {};
  }
}

function readOptional(path) {
  try {
    return readFileSync(join(repoRoot, path), "utf8");
  } catch {
    return "";
  }
}

function printList(items) {
  if (items.length === 0) {
    console.log("- none");
    return;
  }
  for (const item of items) {
    console.log(`- ${item}`);
  }
}
