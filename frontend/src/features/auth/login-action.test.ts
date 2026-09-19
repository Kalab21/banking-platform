import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * No session before the second factor.
 *
 * The form can only ask for a code; what matters is that nothing writes the
 * session cookie until the request carrying that code comes back with a token.
 * This exercises the server action itself, with the gateway call and the cookie
 * writer replaced, because the cookie is the thing that would let someone in.
 */

vi.mock("server-only", () => ({}));

const setSessionCookie = vi.fn();
const redirect = vi.fn(() => {
  // next/navigation's redirect throws to unwind the action; mirroring that
  // keeps the control flow honest.
  throw new Error("NEXT_REDIRECT");
});
const apiLogin = vi.fn();

vi.mock("next/navigation", () => ({ redirect }));
vi.mock("@/lib/session", () => ({ setSessionCookie, clearSessionCookie: vi.fn() }));
vi.mock("@/lib/api/banking", () => ({ login: apiLogin, register: vi.fn() }));

const { loginAction } = await import("@/features/auth/actions");

function credentials(code?: string): FormData {
  const form = new FormData();
  form.set("username", "ada.lovelace");
  form.set("password", "DemoPassword123!");
  if (code !== undefined) form.set("totpCode", code);
  return form;
}

beforeEach(() => {
  setSessionCookie.mockClear();
  redirect.mockClear();
  apiLogin.mockReset();
});

describe("signing in to an account with two-factor enabled", () => {
  it("writes no session when the backend answers twoFactorRequired", async () => {
    apiLogin.mockResolvedValue({ twoFactorRequired: true, token: null });

    const state = await loginAction({}, credentials());

    expect(state.twoFactorRequired).toBe(true);
    expect(setSessionCookie).not.toHaveBeenCalled();
    expect(redirect).not.toHaveBeenCalled();
  });

  it("writes no session when the code is refused", async () => {
    const { ApiError } = await import("@/lib/api/client");
    apiLogin.mockRejectedValue(new ApiError(401, "", "/api/auth/login"));

    const state = await loginAction({}, credentials("000000"));

    expect(state.twoFactorRequired).toBe(true);
    expect(state.error).toMatch(/authentication code/i);
    expect(setSessionCookie).not.toHaveBeenCalled();
  });

  it("writes the session only once a token comes back with the code", async () => {
    apiLogin.mockResolvedValue({ token: "issued-after-totp", expiresIn: 86_400_000 });

    await expect(loginAction({}, credentials("123456"))).rejects.toThrow("NEXT_REDIRECT");

    expect(apiLogin).toHaveBeenCalledWith("ada.lovelace", "DemoPassword123!", "123456");
    expect(setSessionCookie).toHaveBeenCalledWith("issued-after-totp", 86_400_000);
  });

  it("refuses a malformed code without calling the gateway at all", async () => {
    const state = await loginAction({}, credentials("12"));

    expect(state.twoFactorRequired).toBe(true);
    expect(apiLogin).not.toHaveBeenCalled();
    expect(setSessionCookie).not.toHaveBeenCalled();
  });
});
