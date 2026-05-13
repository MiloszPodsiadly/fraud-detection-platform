export function WorkQueueFooter({ hasNext, isLoading, appliedSortLabel, onLoadMore }) {
  return (
    <div className="workQueueFooter">
      <span>{hasNext ? "More cases available" : "End of loaded queue"} - {appliedSortLabel}</span>
      <button
        className="secondaryButton"
        type="button"
        disabled={!hasNext || isLoading}
        onClick={onLoadMore}
        aria-label="Load more fraud cases"
      >
        {isLoading ? "Loading..." : "Load more"}
      </button>
    </div>
  );
}
