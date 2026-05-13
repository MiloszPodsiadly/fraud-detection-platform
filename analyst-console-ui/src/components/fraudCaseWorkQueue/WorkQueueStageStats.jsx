export function WorkQueueStageStats({ stages }) {
  return (
    <div className="workQueueStageStats" aria-label="Loaded fraud case workflow stage counts">
      <span className="workQueueStageStat">
        <strong>Loaded unstarted</strong>
        <span>{stages.unstarted}</span>
      </span>
      <span className="workQueueStageStat">
        <strong>Loaded in progress</strong>
        <span>{stages.inProgress}</span>
      </span>
      <span className="workQueueStageStat">
        <strong>Loaded ready to submit</strong>
        <span>{stages.readyToSubmit}</span>
      </span>
    </div>
  );
}
