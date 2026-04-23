export class ApiError extends Error {
  constructor({ status, error, message, details }) {
    super(message || `Request failed with status ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.error = error;
    this.details = details || [];
  }

  get isUnauthorized() {
    return this.status === 401;
  }

  get isForbidden() {
    return this.status === 403;
  }
}
