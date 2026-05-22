import { normalizeEvidenceCode } from "./evidenceSummaryDisplay.js";

export function boundedEvidenceTitleForType(evidenceType) {
  switch (normalizeEvidenceCode(evidenceType)) {
    case "MODEL_SIGNAL":
      return "Model signal";
    case "RULE_MATCH":
      return "Rule evidence";
    case "PROFILE_DEVIATION":
      return "Profile deviation evidence";
    case "VELOCITY_CHECK":
      return "Velocity evidence";
    case "LINK_ANALYSIS":
      return "Linked-entity evidence";
    case "DEVICE_SIGNAL":
      return "Device evidence";
    case "LOCATION_SIGNAL":
      return "Location evidence";
    case "MERCHANT_SIGNAL":
      return "Merchant evidence";
    case "DIAGNOSTIC":
      return "Diagnostic evidence";
    default:
      return "Evidence item";
  }
}

export function boundedEvidenceDescription() {
  return "Bounded evidence metadata derived from the fraud-case evidence summary.";
}
