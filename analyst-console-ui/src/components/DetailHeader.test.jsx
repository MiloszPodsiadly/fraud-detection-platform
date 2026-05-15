import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DetailHeader } from "./DetailHeader.jsx";

describe("DetailHeader", () => {
  it("renders semantic breadcrumb and a caller-provided heading id", () => {
    render(
      <DetailHeader
        title="Alert detail"
        entityType="Alert"
        entityId="alert-1"
        workspaceLabel="Fraud Transaction"
        actionState="Decision action available"
        headingId="detail-heading-alert-alert-1"
      />
    );

    expect(screen.getByRole("navigation", { name: "Breadcrumb" })).toHaveTextContent("Fraud Transaction > Alert > alert-1");
    expect(document.querySelector(".detailHeader")).toHaveAttribute("aria-labelledby", "detail-heading-alert-alert-1");
    expect(screen.getByRole("heading", { name: "Alert detail" })).toHaveAttribute("id", "detail-heading-alert-alert-1");
  });

  it("supports multiple instances without duplicate heading ids", () => {
    render(
      <>
        <DetailHeader title="Alert detail" entityType="Alert" entityId="alert-1" headingId="detail-heading-alert-alert-1" />
        <DetailHeader title="Fraud case detail" entityType="Fraud case" entityId="case-1" headingId="detail-heading-fraud-case-case-1" />
      </>
    );

    expect(document.querySelectorAll("#detail-heading-alert-alert-1")).toHaveLength(1);
    expect(document.querySelectorAll("#detail-heading-fraud-case-case-1")).toHaveLength(1);
  });

  it("renders status metadata once when risk level is absent", () => {
    render(
      <DetailHeader
        title="Fraud case detail"
        entityType="Fraud case"
        entityId="case-1"
        status="OPEN"
        headingId="detail-heading-fraud-case-case-1"
      />
    );

    expect(screen.getByText("Status")).toBeInTheDocument();
    expect(document.querySelectorAll(".statusPill")).toHaveLength(1);
  });

  it("renders risk badge alongside status metadata when risk level exists", () => {
    render(
      <DetailHeader
        title="Alert detail"
        entityType="Alert"
        entityId="alert-1"
        status="OPEN"
        riskLevel="HIGH"
        headingId="detail-heading-alert-alert-1"
      />
    );

    expect(screen.getByText("Status")).toBeInTheDocument();
    expect(screen.getByText("HIGH")).toBeInTheDocument();
    expect(document.querySelectorAll(".statusPill")).toHaveLength(1);
  });
});
