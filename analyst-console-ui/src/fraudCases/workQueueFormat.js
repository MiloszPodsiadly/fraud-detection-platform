export function formatDurationFromSeconds(seconds) {
  if (seconds === null || seconds === undefined) {
    return "Unknown";
  }

  const totalSeconds = Number(seconds);
  if (!Number.isFinite(totalSeconds) || totalSeconds < 0) {
    return "Unknown";
  }

  if (totalSeconds < 60) {
    return "<1m";
  }

  const totalMinutes = Math.floor(totalSeconds / 60);
  const totalHours = Math.floor(totalMinutes / 60);
  const days = Math.floor(totalHours / 24);
  if (days >= 90) {
    return "90d+";
  }
  if (days > 0) {
    return `${days}d ${totalHours % 24}h`;
  }
  if (totalHours > 0) {
    return `${totalHours}h ${totalMinutes % 60}m`;
  }
  return `${totalMinutes}m`;
}

export function formatAgeAgo(seconds) {
  const formatted = formatDurationFromSeconds(seconds);
  return formatted === "Unknown" ? formatted : `${formatted} ago`;
}
