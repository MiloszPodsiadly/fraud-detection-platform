export function WorkQueueActiveChips({ activeFilters, appliedSortLabel }) {
  return (
    <div className="workQueueChips" aria-label="Applied fraud case work queue filters">
      <span className="tag">Sort: {appliedSortLabel}</span>
      {activeFilters.length === 0 ? (
        <span className="tag">All queue cases</span>
      ) : activeFilters.map((filter) => (
        <span className="tag" key={filter}>{filter}</span>
      ))}
    </div>
  );
}
