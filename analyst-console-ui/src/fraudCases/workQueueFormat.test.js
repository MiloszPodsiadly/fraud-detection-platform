import { describe, expect, it } from "vitest";
import { formatDurationFromSeconds } from "./workQueueFormat.js";

describe("work queue duration formatting", () => {
  it.each([
    [null, "Unknown"],
    [undefined, "Unknown"],
    [-1, "Unknown"],
    [30, "<1m"],
    [600, "10m"],
    [7500, "2h 5m"],
    [183600, "2d 3h"]
  ])("formats %s as %s", (seconds, expected) => {
    expect(formatDurationFromSeconds(seconds)).toBe(expected);
  });
});
