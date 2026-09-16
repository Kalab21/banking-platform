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

  try {
    const auth = await apiLogin(parsed.data.username, parsed.data.password);
    await setSessionCookie(auth.token, auth.expiresIn);
  } catch (error) {
    if (error instanceof ApiError) {
      // 401 here means bad credentials, not an expired session.
      if (error.status === 401) return { error: "Incorrect username or password." };
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
