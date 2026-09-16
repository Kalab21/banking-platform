import type { ApiErrorBody } from "@/types/api";

/**
 * Transport error types.
 *
 * Kept free of `server-only` so they can be imported by client components that
 * render a failure, and exercised directly in unit tests.
 */

/** A non-2xx response from the gateway, carrying the backend's own error body when present. */
export class ApiError extends Error {
  readonly status: number;
  readonly path: string;
  readonly body: ApiErrorBody | null;

  constructor(status: number, message: string, path: string, body: ApiErrorBody | null = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.path = path;
    this.body = body;
  }

  /** The session is gone or the token expired — the caller should send the user back to login. */
  get isUnauthenticated(): boolean {
    return this.status === 401;
  }

  /** Authenticated, but the backend refused. Role checks live on the backend, not here. */
  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }

  /** A message safe to show a user, preferring the backend's own wording. */
  get userMessage(): string {
    if (this.body?.message) return this.body.message;
    switch (this.status) {
      case 400:
        return "That request was not valid. Check the details and try again.";
      case 401:
        return "Your session has expired. Please sign in again.";
      case 403:
        return "You do not have permission to do that.";
      case 404:
        return "We could not find what you were looking for.";
      case 409:
        return "That conflicts with something that already exists.";
      case 422:
        return "Some of those details could not be processed.";
      case 503:
        return "That service is temporarily unavailable. Try again shortly.";
      default:
        return this.status >= 500
          ? "Something went wrong on our side. Please try again."
          : "The request could not be completed.";
    }
  }
}

/** The gateway could not be reached at all. Distinct from an HTTP error. */
export class NetworkError extends Error {
  constructor(cause?: unknown) {
    super("Could not reach the banking API. Check that the gateway is running.");
    this.name = "NetworkError";
    this.cause = cause;
  }

  get userMessage(): string {
    return this.message;
  }
}
