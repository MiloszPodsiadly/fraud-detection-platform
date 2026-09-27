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
  isModelIdentityShape,
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

  it.each(modelIdentityCases())("applies shared ML model identity syntax matrix $caseId", ({ field, value, validSyntax }) => {
    const fixture = publicApiFixture("ml-model-identity-cases.json");
    const identity = { ...fixture.canonicalIdentity, [field]: value };

    expect(isModelIdentityShape(identity)).toBe(validSyntax);
  });

  it("accepts an available ML engine with complete canonical model identity", () => {
    expect(isEngineShape({
      ...availableEngine("ml.python.primary", "ML_MODEL"),
      modelIdentity: canonicalModelIdentity()
    })).toBe(true);
  });

  it("preserves historical available ML engines without model identity", () => {
    expect(isEngineShape(availableEngine("ml.python.primary", "ML_MODEL"))).toBe(true);
  });

  it("accepts a timeout ML engine when model identity is absent", () => {
    expect(isEngineShape(operationalMlEngine("TIMEOUT"))).toBe(true);
  });

  it.each(["UNAVAILABLE", "DEGRADED", "TIMEOUT"])(
    "rejects model identity on a %s ML engine",
    (status) => {
      expect(isEngineShape({
        ...operationalMlEngine(status),
        modelIdentity: canonicalModelIdentity()
      })).toBe(false);
    }
  );

  it.each([
    ["rules.primary", "RULES"],
    ["velocity.primary", "VELOCITY"]
  ])("rejects model identity on the %s engine", (engineId, engineType) => {
    expect(isEngineShape({
      ...availableEngine(engineId, engineType),
      modelIdentity: canonicalModelIdentity()
    })).toBe(false);
  });

  it.each(modelIdentityCases().filter(({ validSyntax }) => !validSyntax))(
    "rejects noncanonical model identity in an engine for $caseId",
    ({ field, value }) => {
      expect(isEngineShape({
        ...availableEngine("ml.python.primary", "ML_MODEL"),
        modelIdentity: { ...canonicalModelIdentity(), [field]: value }
      })).toBe(false);
    }
  );

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

function modelIdentityCases() {
  return publicApiFixture("ml-model-identity-cases.json").cases;
}

function canonicalModelIdentity() {
  return publicApiFixture("ml-model-identity-cases.json").canonicalIdentity;
}

function availableEngine(engineId, engineType) {
  return {
    engineId,
    engineType,
    status: "AVAILABLE",
    riskLevel: "HIGH",
    scoreBucket: "HIGH",
    reasonCodes: ["MODEL_HIGH_RISK"]
  };
}

function operationalMlEngine(status) {
  return {
    engineId: "ml.python.primary",
    engineType: "ML_MODEL",
    status,
    riskLevel: null,
    scoreBucket: "UNAVAILABLE",
    reasonCodes: ["ML_MODEL_UNAVAILABLE"]
  };
}

function publicApiFixture(name) {
  return JSON.parse(readFileSync(resolve(process.cwd(), "../contract-fixtures/public-api", name), "utf8"));
}
