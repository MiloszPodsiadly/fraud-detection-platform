export function PaginationControls({
  page,
  size,
  totalPages,
  totalElements,
  label = "transactions",
  pageSizeOptions = [10, 25, 50, 100],
  onPageChange,
  onSizeChange
}) {
  const safeTotalPages = Math.max(totalPages || 0, 1);
  const canGoPrevious = page > 0;
  const canGoNext = page + 1 < safeTotalPages;

  return (
    <div className="paginationBar">
      <div>
        <strong>{totalElements}</strong>
        <span> total {label}</span>
      </div>
      <div className="paginationActions">
        <label className="pageSizeControl">
          Page size
          <select value={size} onChange={(event) => onSizeChange(Number(event.target.value))}>
            {pageSizeOptions.map((option) => (
              <option key={option} value={option}>{option}</option>
            ))}
          </select>
        </label>
        <button className="secondaryButton" type="button" disabled={!canGoPrevious} onClick={() => onPageChange(page - 1)}>
          Previous
        </button>
        <span className="pageIndicator">Page {page + 1} of {safeTotalPages}</span>
        <button className="secondaryButton" type="button" disabled={!canGoNext} onClick={() => onPageChange(page + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}
