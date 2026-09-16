import "server-only";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { decodeJwt, isExpired, primaryRole } from "@/lib/jwt";
import { SESSION_COOKIE } from "@/lib/api/client";
import type { Role } from "@/types/api";

/**
 * Session handling.
 *
 * The JWT is stored in an httpOnly, SameSite=Lax cookie written by a server
 * action. Page JavaScript cannot read it, which removes the XSS token-theft
 * route that `localStorage` would open up. The cookie's lifetime is taken from
 * the token's own `expiresIn`, so the browser drops it exactly when the backend
 * stops honouring it.
 *
 * The role carried here drives navigation only. It is read from a token the
 * browser cannot forge into a cookie it cannot write, but it is still never an
 * authorisation decision: the backend re-verifies the signature at the gateway
 * and enforces `@PreAuthorize` in the services. If the UI and the backend ever
 * disagree, the backend wins and the user sees a 403.
 */

export interface Session {
  token: string;
  userId: number;
  username: string;
  role: Role;
  expiresAt: number;
}

export async function getSession(): Promise<Session | null> {
  const store = await cookies();
  const token = store.get(SESSION_COOKIE)?.value;
  if (!token) return null;

  const claims = decodeJwt(token);
  if (!claims || isExpired(claims)) return null;

  return {
    token,
    userId: claims.userId,
    username: claims.sub,
    role: primaryRole(claims),
    expiresAt: claims.exp * 1000,
  };
}

/** For pages that require a signed-in user. Sends anonymous visitors to login. */
export async function requireSession(): Promise<Session> {
  const session = await getSession();
  if (!session) redirect("/login");
  return session;
}

/**
 * For pages only staff may open.
 *
 * This hides the page; it does not secure the data. The endpoints behind it
 * carry their own `@PreAuthorize` checks, which is what actually protects them.
 */
export async function requireStaffSession(): Promise<Session> {
  const session = await requireSession();
  if (session.role !== "EMPLOYEE" && session.role !== "ADMIN") redirect("/dashboard");
  return session;
}

export async function setSessionCookie(token: string, expiresInMs: number): Promise<void> {
  const store = await cookies();
  store.set(SESSION_COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: Math.floor(expiresInMs / 1000),
  });
}

export async function clearSessionCookie(): Promise<void> {
  const store = await cookies();
  store.delete(SESSION_COOKIE);
}
