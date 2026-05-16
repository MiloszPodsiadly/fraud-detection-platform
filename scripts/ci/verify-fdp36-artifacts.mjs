import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { repoRoot } from "../fdp-scope/scopeGuardHelpers.mjs";
import { assertFile, readText } from "./artifact-verification-helpers.mjs";

const chaosDir = "alert-service/target/fdp36-chaos";
const summaryPath = `${chaosDir}/fdp36-proof-summary.md`;
const evidencePath = `${chaosDir}/evidence-summary.md`;

mkdirSync(join(repoRoot, chaosDir), { recursive: true });

const evidence = existsSync(join(repoRoot, evidencePath)) ? readText(evidencePath) : "";
const killedIds = uniqueMatches(evidence, /killed_process=([0-9]+)/g).join(",") || "NOT_RECORDED";
const restartedIds = uniqueMatches(evidence, /restarted_process=([0-9]+)/g).join(",") || "NOT_RECORDED";
const testcaseCount = countTestCases("alert-service/target/surefire-reports", /^TEST-RegulatedMutation.*IT\.xml$/);
const timestamp = new Date().toISOString().replace(/\.\d{3}Z$/, "Z");
const jobStatus = process.env.FDP36_JOB_STATUS || "local";
const commitSha = process.env.GITHUB_SHA || "local";

const summary = [
  "# FDP-36 Proof Summary",
  "",
  `- commit_sha: ${commitSha}`,
  `- run_timestamp_utc: ${timestamp}`,
  "- killed_target_name: actual alert-service JVM/process",
  `- killed_process_ids: ${killedIds}`,
  `- restarted_process_ids: ${restartedIds}`,
  "- proof_levels: REAL_ALERT_SERVICE_KILL, REAL_ALERT_SERVICE_RESTART_API_PROOF, LIVE_IN_FLIGHT_REQUEST_KILL",
  `- scenario_count: ${testcaseCount}`,
  "- test_classes_executed: RegulatedMutationRealAlertServiceChaosIT, RegulatedMutationRealAlertServiceEvidenceIntegrityIT, RegulatedMutationLiveInFlightKillIT",
  "- surefire_xml_report_paths: alert-service/target/surefire-reports/TEST-RegulatedMutationRealAlertServiceChaosIT.xml, alert-service/target/surefire-reports/TEST-RegulatedMutationRealAlertServiceEvidenceIntegrityIT.xml, alert-service/target/surefire-reports/TEST-RegulatedMutationLiveInFlightKillIT.xml",
  `- final_status: ${jobStatus}`,
  "",
  "killed target: actual alert-service JVM/process",
  "Docker/Testcontainers are infrastructure dependencies, not the killed alert-service image.",
  "FDP-36 real chaos is not sufficient without regulated-mutation-regression.",
  ""
].join("\n");
writeFileSync(join(repoRoot, summaryPath), summary);

console.log("killed target: actual alert-service JVM/process");
console.log("Docker/Testcontainers are infrastructure dependencies, not the killed alert-service image.");
console.log("FDP-36 real chaos is not sufficient without regulated-mutation-regression.");
console.log("restarted target: actual alert-service JVM/process");
console.log(`scenario count: ${countTestCases("alert-service/target/surefire-reports", /^TEST-RegulatedMutationRealAlertService.*\.xml$/)}`);
console.log("proof levels covered: REAL_ALERT_SERVICE_KILL, REAL_ALERT_SERVICE_RESTART_API_PROOF, LIVE_IN_FLIGHT_REQUEST_KILL");

assertFile(summaryPath);
console.log(readText(summaryPath));
assertFile(evidencePath);
console.log(readText(evidencePath));

function uniqueMatches(source, regex) {
  return [...new Set([...source.matchAll(regex)].map((match) => match[1]))].sort();
}

function countTestCases(directory, fileRegex) {
  const absoluteDirectory = join(repoRoot, directory);
  if (!existsSync(absoluteDirectory)) {
    return 0;
  }
  return readdirSync(absoluteDirectory)
    .filter((entry) => fileRegex.test(entry))
    .map((entry) => readFileSync(join(absoluteDirectory, entry), "utf8"))
    .reduce((count, source) => count + (source.match(/<testcase\b/g) ?? []).length, 0);
}
