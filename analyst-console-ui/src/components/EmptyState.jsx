export function EmptyState({ title, message }) {
  return (
    <div className="statePanel">
      <h3>{title}</h3>
      <p>{message}</p>
    </div>
  );
}
