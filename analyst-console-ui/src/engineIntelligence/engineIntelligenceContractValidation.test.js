import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import {
  COMPARED_ENGINE_IDS,
  COMPARISON_TYPE,
  ENGINE_ORDER,
  ENGINE_TYPE_BY_ID,
  MAX_ENGINE_INTELLIGENCE_ENGINES,
  isCanonicalUtcTimestamp,
  isComparisonShape,
  isDiagnosticSignalShape,
  isEngineIntelligenceResponseShape,
  isEngineShape,
  isWarningShape,
  safeString
} from "./engineIntelligenceContractValidation.js";

describe("engineIntelligenceContractValidation", () => {
  it("matches shared canonical engine registry fixture", () => {
    const registry = sharedFixture("engine_registry_contract.json");

    expect(registry.maxEngineCount).toBe(MAX_ENGINE_INTELLIGENCE_ENGINES);
    expect(registry.order).toEqual(ENGINE_ORDER);
    expect(registry.comparison.comparisonType).toBe(COMPARISON_TYPE);
    expect(registry.comparison.comparedEngineIds).toEqual(COMPARED_ENGINE_IDS);
    expect(Object.fromEntries(registry.engines.map((engine) => [engine.engineId, engine.engineType]))).toEqual(ENGINE_TYPE_BY_ID);
    expect(new Set(registry.engines.map((engine) => engine.engineId)).size).toBe(registry.engines.length);
  });

  it("accepts shared three-engine golden fixture", () => {
    expect(isValidEngineIntelligence(sharedFixture("engine_intelligence_three_engine_golden.json"))).toBe(true);
  });

  it("accepts full-path public API composition fixture", () => {
    expect(isEngineIntelligenceResponseShape(
      publicApiFixture("engine-intelligence-full-path-composition-response.json")
    )).toBe(true);
  });

  it.each(sharedInvalidEngineIntelligenceCases())("rejects shared invalid semantic case $caseId", ({ engineIntelligence }) => {
    expect(isValidEngineIntelligence(engineIntelligence)).toBe(false);
  });

  it.each(sharedInvalidEngineIntelligenceResponseCases())("rejects shared invalid response status case $caseId", ({ engineIntelligenceResponse }) => {
    expect(isEngineIntelligenceResponseShape(engineIntelligenceResponse)).toBe(false);
  });

  it.each(timestampCases())("applies shared timestamp matrix $caseId", ({ value, valid }) => {
    expect(isCanonicalUtcTimestamp(value)).toBe(valid);
  });

  it.each(stringBoundaryCases())("applies shared bounded-string matrix $caseId", ({ value, maxLength, valid }) => {
    expect(safeString(value, maxLength)).toBe(valid);
  });

  it("rejects extra field at every nested public DTO", () => {
    const fixture = sharedFixture("engine_intelligence_three_engine_golden.json");

    expect(isComparisonShape({ ...fixture.comparison, extra: "x" })).toBe(false);
    expect(isEngineShape({ ...fixture.engines[0], extra: "x" })).toBe(false);
    expect(isDiagnosticSignalShape({ ...fixture.diagnosticSignals[0], extra: "x" })).toBe(false);
    expect(isWarningShape({ warningCode: "ENGINE_RESULT_LIMIT_APPLIED", count: 1, extra: "x" })).toBe(false);
  });
});

function isValidEngineIntelligence(value) {
  return isEngineIntelligenceResponseShape({
    status: "AVAILABLE",
    ...value
  });
}

function sharedInvalidEngineIntelligenceCases() {
  return sharedFixture("invalid_semantic_cases.json").cases
    .filter((semanticCase) => semanticCase.category === "engine-intelligence");
}

function sharedInvalidEngineIntelligenceResponseCases() {
  return sharedFixture("invalid_semantic_cases.json").cases
    .filter((semanticCase) => semanticCase.category === "engine-intelligence-response");
}

function sharedFixture(name) {
  return JSON.parse(readFileSync(resolve(
    process.cwd(),
    "../common-events/src/test/resources/fixtures/engine-intelligence",
    name
  ), "utf8"));
}

function timestampCases() {
  return publicApiFixture("canonical-utc-timestamp-cases.json").cases;
}

function stringBoundaryCases() {
  return publicApiFixture("public-string-boundary-cases.json").cases;
}

function publicApiFixture(name) {
  return JSON.parse(readFileSync(resolve(process.cwd(), "../contract-fixtures/public-api", name), "utf8"));
}
