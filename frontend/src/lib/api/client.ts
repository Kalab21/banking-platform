import "server-only";

import { cookies } from "next/headers";
import type { ApiErrorBody } from "@/types/api";
import { ApiError, NetworkError } from "@/lib/api/errors";

export { ApiError, NetworkError } from "@/lib/api/errors";

/**
 * The single outbound HTTP layer.
 *
 * Every call from this application goes through the API Gateway — no service is
 * ever addressed directly. This module runs only on the server (`server-only`),
 * so the bearer token never reaches the browser: it lives in an httpOnly cookie
 * that JavaScript in the page cannot read.
 */

export const SESSION_COOKIE = "bp_session";

const BASE_URL = process.env.API_GATEWAY_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export async function getToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(SESSION_COOKIE)?.value ?? null;
}

interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  /** Send without the Authorization header — only for the public auth endpoints. */
  anonymous?: boolean;
  /** Seconds to cache. Omit for no caching, which is the right default for account data. */
  revalidate?: number;
  query?: Record<string, string | number | boolean | undefined>;
}

function buildUrl(path: string, query?: RequestOptions["query"]): string {
  const url = new URL(path.startsWith("/") ? path : `/${path}`, BASE_URL);
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) url.searchParams.set(key, String(value));
    }
  }
  return url.toString();
}

/**
 * Performs one request against the gateway and returns the parsed body.
 *
 * Throws `ApiError` for any non-2xx status and `NetworkError` when the gateway
 * is unreachable, so callers can distinguish "the bank said no" from "the bank
 * did not answer".
 */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, anonymous = false, revalidate, query } = options;

  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";

  if (!anonymous) {
    const token = await getToken();
    if (!token) throw new ApiError(401, "No active session", path);
    headers.Authorization = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: revalidate === undefined ? "no-store" : undefined,
      next: revalidate === undefined ? undefined : { revalidate },
    });
  } catch (cause) {
    throw new NetworkError(cause);
  }

  if (!response.ok) {
    let parsed: ApiErrorBody | null = null;
    try {
      const text = await response.text();
      if (text) parsed = JSON.parse(text) as ApiErrorBody;
    } catch {
      // A non-JSON error body (the gateway returns an empty 401) is expected.
    }
    throw new ApiError(response.status, parsed?.message ?? response.statusText, path, parsed);
  }

  if (response.status === 204) return undefined as T;

  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

/**
 * Runs a read and converts an expected "nothing there" into a fallback.
 *
 * Several dashboard panels are optional: a brand-new customer has no statistics
 * row and no credit-card record yet. A 404 or 403 on those is a normal empty
 * state, not a page failure.
 */
export async function apiFetchOptional<T>(
  path: string,
  options: RequestOptions = {},
  fallback: T,
): Promise<T> {
  try {
    return await apiFetch<T>(path, options);
  } catch (error) {
    if (error instanceof ApiError && (error.isNotFound || error.isForbidden)) return fallback;
    if (error instanceof ApiError && error.isUnauthenticated) throw error;
    if (error instanceof NetworkError) throw error;
    return fallback;
  }
}
