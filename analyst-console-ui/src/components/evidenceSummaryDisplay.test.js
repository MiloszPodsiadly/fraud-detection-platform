import { describe, expect, it } from "vitest";
import {
  formatCount,
  normalizeEvidenceCode,
  safeArray,
  safeTruncationReason,
  toCountItem
} from "./evidenceSummaryDisplay.js";

describe("evidenceSummaryDisplay", () => {
  it("normalizeEvidenceCode returns known uppercase enum-like value unchanged", () => {
    expect(normalizeEvidenceCode("MODEL_SIGNAL")).toBe("MODEL_SIGNAL");
  });

  it("normalizeEvidenceCode trims safe enum-like value", () => {
    expect(normalizeEvidenceCode(" HIGH_AMOUNT_ACTIVITY ")).toBe("HIGH_AMOUNT_ACTIVITY");
  });

  it.each([
    ["lowercase free text"],
    ["customer-secret account-secret txn-secret"],
    ["raw-model-payload"],
    ["scoreDetails"],
    ["featureSnapshot"],
    ["A".repeat(81)],
    [null],
    [undefined],
    [{}],
    [[]],
    [""]
  ])("normalizeEvidenceCode rejects unsafe value %#", (value) => {
    expect(normalizeEvidenceCode(value)).toBe("UNKNOWN");
  });

  it("safeTruncationReason returns the bounded known value", () => {
    expect(safeTruncationReason("LINKED_ALERT_LIMIT_EXCEEDED")).toBe("LINKED_ALERT_LIMIT_EXCEEDED");
  });

  it.each([
    ["customer-secret raw truncation reason"],
    ["raw-model-payload"],
    ["UNKNOWN_LIMIT"],
    [null]
  ])("safeTruncationReason rejects unsafe value %#", (value) => {
    expect(safeTruncationReason(value)).toBe("Bounded summary limit reached");
  });

  it.each([
    [0, "0"],
    [7, "7"],
    ["2", "2"],
    [null, "0"],
    [Number.NaN, "0"],
    [Infinity, "0"],
    [-1, "0"],
    [{}, "0"],
    [[], "0"],
    ["not-a-number", "0"]
  ])("formatCount maps %# to %s", (value, expected) => {
    expect(formatCount(value)).toBe(expected);
  });

  it.each([
    [[1], [1]],
    [null, []],
    ["not-array", []]
  ])("safeArray maps %#", (value, expected) => {
    expect(safeArray(value)).toEqual(expected);
  });

  it("toCountItem maps valid source and count correctly", () => {
    expect(toCountItem({ source: "ML_SCORING", count: 1 }, "source")).toEqual({
      label: "ML_SCORING",
      count: "1"
    });
  });

  it.each([
    [null],
    ["raw-source-secret"],
    [42],
    [{}],
    [[]]
  ])("toCountItem maps malformed item %# to UNKNOWN and 0", (item) => {
    expect(toCountItem(item, "source")).toEqual({ label: "UNKNOWN", count: "0" });
  });

  it("toCountItem maps malformed label and malformed count safely", () => {
    const item = toCountItem({ source: "customer-secret account-secret txn-secret", count: "not-a-number" }, "source");

    expect(item).toEqual({ label: "UNKNOWN", count: "0" });
    expect(JSON.stringify(item)).not.toContain("customer-secret");
    expect(JSON.stringify(item)).not.toContain("account-secret");
    expect(JSON.stringify(item)).not.toContain("txn-secret");
  });
});
