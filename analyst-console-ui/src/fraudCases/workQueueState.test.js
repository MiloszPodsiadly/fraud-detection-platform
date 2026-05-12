import { describe, expect, it } from "vitest";
import {
  FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS,
  initialFraudCaseWorkQueue,
  initialFraudCaseWorkQueueRequest,
  isInvalidWorkQueueCursorError,
  mergeWorkQueueSlice,
  resetWorkQueueRequestForFilterChange
} from "./workQueueState.js";

describe("fraud case work queue state", () => {
  it("clears cursor and bounds size when filters or sort change", () => {
    const current = {
      ...initialFraudCaseWorkQueueRequest(),
      cursor: "opaque-cursor",
      size: 20
    };

    const next = resetWorkQueueRequestForFilterChange(current, {
      status: "OPEN",
      sort: "updatedAt,asc",
      size: 500
    });

    expect(next).toMatchObject({
      status: "OPEN",
      sort: "updatedAt,asc",
      cursor: null,
      size: 100
    });
  });

  it("appends load-more slices without rendering duplicate case ids twice", () => {
    const first = mergeWorkQueueSlice(initialFraudCaseWorkQueue(), {
      content: [{ caseId: "case-1" }, { caseId: "case-2" }],
      size: 20,
      hasNext: true,
      nextCursor: "next-1",
      sort: "createdAt,desc"
    });

    const second = mergeWorkQueueSlice(first, {
      content: [{ caseId: "case-2" }, { caseId: "case-3" }],
      size: 20,
      hasNext: false,
      nextCursor: null,
      sort: "createdAt,desc"
    }, { append: true });

    expect(second.content.map((item) => item.caseId)).toEqual(["case-1", "case-2", "case-3"]);
    expect(second.duplicateCaseIds).toEqual(["case-2"]);
    expect(second.hasNext).toBe(false);
  });

  it("uses neutral sort labels for priority and risk ordering", () => {
    expect(FRAUD_CASE_WORK_QUEUE_SORT_OPTIONS).toEqual(expect.arrayContaining([
      { value: "priority,desc", label: "Priority descending" },
      { value: "priority,asc", label: "Priority ascending" },
      { value: "riskLevel,desc", label: "Risk descending" },
      { value: "riskLevel,asc", label: "Risk ascending" }
    ]));
  });

  it("classifies invalid cursor and cursor-page-combination errors", () => {
    expect(isInvalidWorkQueueCursorError({ status: 400, error: "INVALID_CURSOR" })).toBe(true);
    expect(isInvalidWorkQueueCursorError({ status: 400, message: "INVALID_CURSOR_PAGE_COMBINATION" })).toBe(true);
    expect(isInvalidWorkQueueCursorError({ status: 400, error: "INVALID_FILTER" })).toBe(false);
    expect(isInvalidWorkQueueCursorError({ status: 503, error: "INVALID_CURSOR" })).toBe(false);
  });
});
