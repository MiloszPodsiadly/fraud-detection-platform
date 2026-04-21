import { formatAmount, formatDateTime, formatScore } from "../utils/format.js";
import { FilterBar } from "./FilterBar.jsx";
import { PaginationControls } from "./PaginationControls.jsx";
import { RiskBadge } from "./RiskBadge.jsx";

export function FraudCasePanel({ fraudCasePage, filters, onFiltersChange, onPageChange, onPageSizeChange, onOpenCase }) {
  const rapidCases = fraudCasePage.content || [];

  return (
    <section className="panel fraudCasePanel" aria-labelledby="fraud-cases-title">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Fraud cases</p>
          <h2 id="fraud-cases-title">Rapid transfer bursts</h2>
          <p className="sectionCopy">
            Cases created when grouped transfers exceed 20,000 PLN inside a 1 minute window.
          </p>
        </div>
        <div className="fraudCaseCounter">
          <strong>{fraudCasePage.totalElements}</strong>
          <span>Open burst cases</span>
        </div>
      </div>

      <FilterBar
        filters={filters}
        onChange={onFiltersChange}
        placeholder="Case, customer, transaction, reason"
        showRisk={false}
        statusOptions={["ALL", "OPEN", "IN_REVIEW", "CONFIRMED_FRAUD", "FALSE_POSITIVE", "CLOSED"]}
      />

      {rapidCases.length === 0 ? (
        <div className="fraudCaseEmpty">
          <strong>RAPID_TRANSFER_BURST_20K_PLN</strong>
          <span>No active grouped-transfer fraud cases.</span>
        </div>
      ) : (
        <>
          <div className="fraudCaseGrid">
            {rapidCases.map((fraudCase) => (
              <article className="fraudCaseCard" key={fraudCase.caseId}>
                <div className="fraudCaseCardHeader">
                  <div>
                    <span className="statusPill">{fraudCase.status}</span>
                    <h3>{fraudCase.suspicionType}</h3>
                  </div>
                  <strong>{formatPln(fraudCase.totalAmountPln)}</strong>
                </div>

                <dl className="fraudCaseFacts">
                  <div>
                    <dt>Customer</dt>
                    <dd>{fraudCase.customerId}</dd>
                  </div>
                  <div>
                    <dt>Window</dt>
                    <dd>{fraudCase.aggregationWindow || "PT1M"}</dd>
                  </div>
                  <div>
                    <dt>Transfers</dt>
                    <dd>{fraudCase.transactions?.length || fraudCase.transactionIds?.length || 0}</dd>
                  </div>
                  <div>
                    <dt>Time range</dt>
                    <dd>{formatDateTime(fraudCase.firstTransactionAt)} - {formatDateTime(fraudCase.lastTransactionAt)}</dd>
                  </div>
                </dl>

                <div className="fraudCaseTransactions">
                  {(fraudCase.transactions || []).slice(0, 5).map((transaction) => (
                    <div className="fraudCaseTransaction" key={transaction.transactionId}>
                      <div>
                        <strong>{transaction.transactionId}</strong>
                        <span>{formatAmount(transaction.transactionAmount)} / {formatPln(transaction.amountPln)}</span>
                      </div>
                      <div className="fraudCaseRisk">
                        <RiskBadge riskLevel={transaction.riskLevel} />
                        <span>{formatScore(transaction.fraudScore)}</span>
                      </div>
                    </div>
                  ))}
                </div>
                <button className="rowButton fraudCaseOpenButton" type="button" onClick={() => onOpenCase(fraudCase.caseId)}>
                  Review case
                </button>
              </article>
            ))}
          </div>
          <PaginationControls
            label="fraud cases"
            page={fraudCasePage.page}
            size={fraudCasePage.size}
            totalPages={fraudCasePage.totalPages}
            totalElements={fraudCasePage.totalElements}
            pageSizeOptions={[4, 10, 20]}
            onPageChange={onPageChange}
            onSizeChange={onPageSizeChange}
          />
        </>
      )}
    </section>
  );
}

function formatPln(value) {
  if (value === null || value === undefined) {
    return "N/A";
  }

  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency: "PLN"
  }).format(Number(value));
}
