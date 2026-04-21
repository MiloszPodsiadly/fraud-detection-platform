export function LoadingPanel({ label }) {
  return (
    <div className="statePanel">
      <div className="spinner" />
      <p>{label}</p>
    </div>
  );
}
