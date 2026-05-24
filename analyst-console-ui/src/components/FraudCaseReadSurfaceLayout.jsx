export function FraudCaseReadSurfaceLayout({ children }) {
  return (
    <div
      className="fraudCaseReadSurfaceLayout"
      data-testid="fraud-case-read-surface-layout"
      aria-label="Read-only investigation context"
    >
      {children}
    </div>
  );
}
