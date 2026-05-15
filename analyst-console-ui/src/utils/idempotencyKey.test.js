import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createIdempotencyKey, secureRandomId } from "./idempotencyKey.js";

describe("idempotencyKey", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses crypto.randomUUID when available", () => {
    vi.stubGlobal("crypto", { randomUUID: vi.fn(() => "uuid-1") });

    expect(createIdempotencyKey("alert-decision")).toBe("alert-decision-uuid-1");
  });

  it("falls back to crypto.getRandomValues when randomUUID is unavailable", () => {
    vi.stubGlobal("crypto", {
      getRandomValues: vi.fn((bytes) => {
        bytes.set([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]);
        return bytes;
      })
    });

    expect(createIdempotencyKey("fraud-case-update")).toBe("fraud-case-update-00010203-0405-4607-8809-0a0b0c0d0e0f");
  });

  it("does not embed raw entity IDs", () => {
    vi.stubGlobal("crypto", { randomUUID: vi.fn(() => "uuid-1") });

    const key = createIdempotencyKey("alert-decision");

    expect(key).toMatch(/^alert-decision-/);
    expect(key).not.toContain("alert-sensitive-1");
  });

  it("generates different keys for repeated calls", () => {
    vi.stubGlobal("crypto", { randomUUID: vi.fn().mockReturnValueOnce("uuid-1").mockReturnValueOnce("uuid-2") });

    expect(createIdempotencyKey("alert-decision")).not.toBe(createIdempotencyKey("alert-decision"));
  });

  it("getRandomValues fallback is non-deterministic across repeated calls", () => {
    let seed = 0;
    vi.stubGlobal("crypto", {
      getRandomValues: vi.fn((bytes) => {
        bytes.fill(seed);
        seed += 1;
        return bytes;
      })
    });

    expect(createIdempotencyKey("fraud-case-update")).not.toBe(createIdempotencyKey("fraud-case-update"));
  });

  it("fails closed when Web Crypto is unavailable", () => {
    vi.stubGlobal("crypto", undefined);

    expect(() => secureRandomId()).toThrow("Web Crypto is required to create an idempotency key.");
  });

  it("does not pass domain identifiers into idempotency key generation call sites", () => {
    const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
    const callSites = [
      "analyst-console-ui/src/components/AnalystDecisionForm.jsx",
      "analyst-console-ui/src/pages/FraudCaseDetailsPage.jsx"
    ];

    for (const callSite of callSites) {
      const source = readFileSync(resolve(repoRoot, callSite), "utf8");
      expect(source).not.toMatch(/createIdempotencyKey\([^)]*,\s*(alertId|caseId|transactionId|customerId|correlationId|caseNumber)/);
      expect(source).not.toMatch(/`[^`]*\$\{(?:alertId|caseId|transactionId|customerId|correlationId|caseNumber)\}[^`]*`/);
    }
  });
});
