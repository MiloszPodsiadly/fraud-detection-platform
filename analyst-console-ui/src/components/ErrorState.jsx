export function ErrorState({ message, onRetry }) {
  return (
    <div className="statePanel errorPanel">
      <h3>Unable to load data</h3>
      <p>{message}</p>
      <button className="secondaryButton" type="button" onClick={onRetry}>Try again</button>
    </div>
  );
}
