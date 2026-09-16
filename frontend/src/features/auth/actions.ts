"use server";

import { redirect } from "next/navigation";
import { ApiError, NetworkError } from "@/lib/api/client";
import { login as apiLogin, register as apiRegister } from "@/lib/api/banking";
import { clearSessionCookie, setSessionCookie } from "@/lib/session";
import { fieldErrors, loginSchema, registerSchema } from "@/lib/validation";

/**
 * Server actions for authentication.
 *
 * The token returned by the gateway is written straight into an httpOnly cookie
 * on the server and never sent to the browser as readable data.
 */

export interface AuthFormState {
  error?: string;
  fields?: Record<string, string>;
  /**
   * The password was accepted but the account carries a second factor. The form
   * re-renders asking for a TOTP code; no session exists yet.
   */
  twoFactorRequired?: boolean;
  /** Carried across the challenge step so the user does not retype it. */
  username?: string;
}

export async function loginAction(
  _prev: AuthFormState,
  formData: FormData,
): Promise<AuthFormState> {
  const parsed = loginSchema.safeParse({
    username: formData.get("username"),
    password: formData.get("password"),
  });

  if (!parsed.success) return { fields: fieldErrors(parsed.error) };

  const rawCode = String(formData.get("totpCode") ?? "").trim();
  if (rawCode && !/^\d{6}$/.test(rawCode)) {
    return {
      twoFactorRequired: true,
      username: parsed.data.username,
      fields: { totpCode: "Enter the 6-digit code from your authenticator app" },
    };
  }

  try {
    const auth = await apiLogin(parsed.data.username, parsed.data.password, rawCode || undefined);

    // Password accepted, second factor still outstanding: no token was issued.
    if (auth.twoFactorRequired || !auth.token) {
      return { twoFactorRequired: true, username: parsed.data.username };
    }

    await setSessionCookie(auth.token, auth.expiresIn);
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.status === 401) {
        // The backend uses the same status for a bad password and a bad code.
        return rawCode
          ? {
              twoFactorRequired: true,
              username: parsed.data.username,
              error: "That authentication code was not accepted. Try the current code.",
            }
          : { error: "Incorrect username or password." };
      }
      return { error: error.userMessage };
    }
    if (error instanceof NetworkError) return { error: error.userMessage };
    return { error: "Could not sign you in. Please try again." };
  }

  redirect("/dashboard");
}

export async function registerAction(
  _prev: AuthFormState,
  formData: FormData,
): Promise<AuthFormState> {
  const parsed = registerSchema.safeParse({
    username: formData.get("username"),
    email: formData.get("email"),
    password: formData.get("password"),
    firstName: formData.get("firstName"),
    lastName: formData.get("lastName"),
    phone: formData.get("phone") || undefined,
  });

  if (!parsed.success) return { fields: fieldErrors(parsed.error) };

  try {
    const auth = await apiRegister(parsed.data);
    if (!auth.token) return { error: "Account created, but no session was issued. Please sign in." };
    await setSessionCookie(auth.token, auth.expiresIn);
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.status === 409) {
        return { error: error.userMessage || "That username or email is already registered." };
      }
      return { error: error.userMessage };
    }
    if (error instanceof NetworkError) return { error: error.userMessage };
    return { error: "Could not create your account. Please try again." };
  }

  redirect("/dashboard");
}

export async function logoutAction(): Promise<void> {
  await clearSessionCookie();
  redirect("/login");
}
