export function JsonInspector({ title, value }) {
  return (
    <section className="subPanel">
      <h3>{title}</h3>
      {value && Object.keys(value).length > 0 ? (
        <pre className="jsonBlock">{JSON.stringify(value, null, 2)}</pre>
      ) : (
        <p className="muted">No data supplied.</p>
      )}
    </section>
  );
}
