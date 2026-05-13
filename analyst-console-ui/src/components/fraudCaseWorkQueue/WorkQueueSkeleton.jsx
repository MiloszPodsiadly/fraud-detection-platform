export function WorkQueueSkeletonRows() {
  return Array.from({ length: 5 }, (_, index) => (
    <tr className="skeletonRow" key={index}>
      {Array.from({ length: 10 }, (__, cellIndex) => (
        <td key={cellIndex}><span className="skeletonBlock" /></td>
      ))}
    </tr>
  ));
}

export function WorkQueueSkeletonCards() {
  return Array.from({ length: 3 }, (_, index) => (
    <div className="workQueueCard skeletonCard" key={index}>
      <span className="skeletonBlock" />
      <span className="skeletonBlock" />
      <span className="skeletonBlock" />
    </div>
  ));
}
