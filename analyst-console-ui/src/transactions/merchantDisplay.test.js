import { describe, expect, it } from "vitest";
import { merchantDisplayValue } from "./merchantDisplay.js";

describe("merchantDisplayValue", () => {
  it("trims safe surrounding whitespace from a bounded merchant name for display", () => {
    expect(merchantDisplayValue({ merchantName: "  Northwind Books  " }, "merchantName"))
      .toBe("Northwind Books");
  });

  it.each([
    ["whitespace only", " \t "],
    ["over the display bound", "x".repeat(129)],
    ["control character", "North\u0000wind"],
    ["bidi control", "North\u202ewind"],
    ["missing value", undefined],
    ["nested value", { name: "Northwind" }]
  ])("rejects a merchant name with %s", (_caseName, merchantName) => {
    expect(merchantDisplayValue({ merchantName }, "merchantName")).toBeNull();
  });

  it("does not normalize other merchant identity fields", () => {
    expect(merchantDisplayValue({ merchantId: " merchant-17 " }, "merchantId")).toBeNull();
  });
});
