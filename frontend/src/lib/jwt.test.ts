import { describe, expect, it } from "vitest";
import { decodeJwt, isExpired, primaryRole } from "@/lib/jwt";

/** Builds an unsigned token with the given payload — enough to exercise decoding. */
function makeToken(payload: Record<string, unknown>): string {
  const encode = (obj: unknown) =>
    Buffer.from(JSON.stringify(obj))
      .toString("base64")
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "");
  return `${encode({ alg: "HS256", typ: "JWT" })}.${encode(payload)}.signature`;
}

describe("decodeJwt", () => {
  it("reads the userId and subject the app needs to build requests", () => {
    const token = makeToken({
      sub: "demo",
      userId: 42,
      roles: ["ROLE_CUSTOMER"],
      iat: 1_700_000_000,
      exp: 1_700_086_400,
    });

    expect(decodeJwt(token)).toEqual({
      sub: "demo",
      userId: 42,
      roles: ["ROLE_CUSTOMER"],
      iat: 1_700_000_000,
      exp: 1_700_086_400,
    });
  });

  it("returns null for anything that is not a three-part token", () => {
    expect(decodeJwt("not-a-token")).toBeNull();
    expect(decodeJwt("")).toBeNull();
    expect(decodeJwt("a.b")).toBeNull();
  });

  it("returns null when the payload is not valid JSON", () => {
    expect(decodeJwt("header.bm90LWpzb24.sig")).toBeNull();
  });

  it("returns null when the userId claim is missing, so callers cannot proceed blindly", () => {
    expect(decodeJwt(makeToken({ sub: "demo", roles: [] }))).toBeNull();
  });
});

describe("isExpired", () => {
  const claims = { sub: "demo", userId: 1, roles: [], iat: 0, exp: 1_700_000_000 };

  it("is true once the expiry has passed", () => {
    expect(isExpired(claims, 1_700_000_001_000)).toBe(true);
  });

  it("is false while the token is still inside its window", () => {
    expect(isExpired(claims, 1_699_999_000_000)).toBe(false);
  });
});

describe("primaryRole", () => {
  it("strips the Spring ROLE_ prefix", () => {
    expect(primaryRole({ sub: "a", userId: 1, roles: ["ROLE_ADMIN"], iat: 0, exp: 0 })).toBe("ADMIN");
    expect(primaryRole({ sub: "a", userId: 1, roles: ["ROLE_EMPLOYEE"], iat: 0, exp: 0 })).toBe(
      "EMPLOYEE",
    );
  });

  it("falls back to the least-privileged role for anything unrecognised", () => {
    expect(primaryRole({ sub: "a", userId: 1, roles: [], iat: 0, exp: 0 })).toBe("CUSTOMER");
    expect(primaryRole({ sub: "a", userId: 1, roles: ["ROLE_WIZARD"], iat: 0, exp: 0 })).toBe(
      "CUSTOMER",
    );
  });
});
