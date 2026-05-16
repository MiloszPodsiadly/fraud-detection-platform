import { readFileSync } from "node:fs";
import { join } from "node:path";
import { repoRoot } from "./fdp-scope/scopeGuardHelpers.mjs";

const workflowPath = ".github/workflows/ci.yml";
const evidencePath = "docs/ci_evidence_map.md";
const planPath = "docs/ci_consolidation_plan.md";

const workflow = readFileSync(join(repoRoot, workflowPath), "utf8");
const evidence = readFileSync(join(repoRoot, evidencePath), "utf8");
const plan = readOptional(planPath);

const currentJobs = parseWorkflowJobs(workflow);
const evidenceSections = parseEvidenceSections(evidence);
const evidenceByName = new Map(evidenceSections.map((section) => [section.name, section]));

const missingEvidence = currentJobs.filter((job) => !evidenceByName.has(job.name));
const staleEvidence = evidenceSections.filter((section) => {
  if (currentJobs.some((job) => job.name === section.name)) {
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

if (plan) {
  const implemented = /Decision:\s*(?:\r?\n\s*-\s*)?implemented/i.test(plan);
  console.log(`\nConsolidation plan implemented marker: ${implemented ? "present" : "missing"}`);
}

if (missingEvidence.length > 0 || staleEvidence.length > 0 || missingReplacementMapping.length > 0) {
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
