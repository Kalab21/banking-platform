import type { JwtClaims, Role } from "@/types/api";

/**
 * Decodes the payload of a JWT without verifying it.
 *
 * Verification is deliberately not done here. The API Gateway is the only
 * authority on whether a token is valid — it re-verifies the signature on every
 * request. This decode exists solely to read the `userId` claim, because the
 * login response body does not carry the user id and almost every downstream
 * endpoint is keyed by it. Treat the result as a hint for building requests and
 * rendering, never as an authorisation decision.
 */
export function decodeJwt(token: string): JwtClaims | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;

  try {
    const payload = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = payload.padEnd(payload.length + ((4 - (payload.length % 4)) % 4), "=");
    const json =
      typeof atob === "function"
        ? atob(padded)
        : Buffer.from(padded, "base64").toString("utf8");

    const claims = JSON.parse(json) as Partial<JwtClaims>;
    if (typeof claims.userId !== "number" || typeof claims.sub !== "string") return null;

    return {
      sub: claims.sub,
      userId: claims.userId,
      roles: Array.isArray(claims.roles) ? claims.roles : [],
      iat: typeof claims.iat === "number" ? claims.iat : 0,
      exp: typeof claims.exp === "number" ? claims.exp : 0,
    };
  } catch {
    return null;
  }
}

/** True when the token's `exp` claim is in the past. */
export function isExpired(claims: JwtClaims, nowMs: number = Date.now()): boolean {
  if (!claims.exp) return false;
  return claims.exp * 1000 <= nowMs;
}

/**
 * Normalises the `roles` claim to a single role.
 *
 * Spring emits authorities as `ROLE_CUSTOMER`; the gateway forwards the first
 * one as `X-User-Role`. This mirrors that choice so the UI and the backend agree
 * on which role is in effect.
 */
export function primaryRole(claims: JwtClaims): Role {
  const raw = claims.roles[0] ?? "";
  const name = raw.startsWith("ROLE_") ? raw.slice(5) : raw;
  return name === "ADMIN" || name === "EMPLOYEE" ? name : "CUSTOMER";
}
