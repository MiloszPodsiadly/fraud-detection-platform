export function LoadingPanel({ label }) {
  return (
    <div className="statePanel" aria-live="polite">
      <div className="spinner" />
      <p>{label}</p>
    </div>
  );
}
