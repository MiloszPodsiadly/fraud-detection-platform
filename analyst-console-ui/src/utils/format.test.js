import { describe, expect, it } from "vitest";
import { formatDateTime } from "./format.js";

describe("formatDateTime", () => {
  it.each([
    ["unparseable text", "not-a-date"],
    ["impossible calendar date", "2026-02-30T12:00:00Z"],
    ["invalid timezone offset", "2026-04-20T09:00:00+18:01"],
    ["invalid Date", new Date(Number.NaN)],
    ["unrepresentable input", Symbol("private")]
  ])("returns the unavailable label for %s", (_label, value) => {
    expect(formatDateTime(value)).toBe("Not available");
  });

  it("preserves normal locale formatting for valid timestamps", () => {
    const timestamp = "2026-04-20T09:00:10Z";
    const expected = new Intl.DateTimeFormat(undefined, {
      dateStyle: "medium",
      timeStyle: "short"
    }).format(new Date(timestamp));

    expect(formatDateTime(timestamp)).toBe(expected);
  });
});
