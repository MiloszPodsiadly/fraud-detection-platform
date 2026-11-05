const REVIEW_RECOMMENDED = Object.freeze({
  label: "Review recommended",
  className: "classificationReview"
});
const NO_REVIEW_RECOMMENDATION = Object.freeze({
  label: "No review recommendation",
  className: "classificationNeutral"
});
const UNKNOWN_RECOMMENDATION = Object.freeze({
  label: "Unknown",
  className: "classificationNeutral"
});

export function alertRecommendationPresentation(value) {
  if (value === true) {
    return REVIEW_RECOMMENDED;
  }
  if (value === false) {
    return NO_REVIEW_RECOMMENDATION;
  }
  return UNKNOWN_RECOMMENDATION;
}
