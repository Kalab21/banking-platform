"use server";

import { redirect } from "next/navigation";
import { ApiError, NetworkError } from "@/lib/api/client";
import { login as apiLogin, register as apiRegister } from "@/lib/api/banking";
import { clearSessionCookie, setSessionCookie } from "@/lib/session";
import { normalizePhone } from "@/lib/phone";
import { fieldErrors, loginSchema, registerFormSchema } from "@/lib/validation";

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
  /** The account was created and a session issued; the wizard shows its last step. */
  completed?: boolean;
  /**
   * What the customer already typed, echoed back so a rejected submission does
   * not empty the wizard.
   *
   * Passwords and the Social Security number are never included. Re-rendering a
   * password puts it back into the HTML for no benefit, and the number is not
   * ours to hand back — the server keeps four digits of it and discards the
   * rest, so there is nothing to echo even if we wanted to.
   */
  values?: {
    firstName?: string;
    middleName?: string;
    lastName?: string;
    email?: string;
    phone?: string;
    username?: string;
    dateOfBirth?: string;
    streetAddress?: string;
    addressLine2?: string;
    city?: string;
    state?: string;
    postalCode?: string;
  };
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
          : {
              // Deliberately the same message whether the username exists or
              // not, and whichever of the two was wrong.
              error: "Incorrect username or password.",
              username: parsed.data.username,
            };
      }
      return { error: error.userMessage, username: parsed.data.username };
    }
    if (error instanceof NetworkError) {
      return { error: error.userMessage, username: parsed.data.username };
    }
    return { error: "Could not sign you in. Please try again.", username: parsed.data.username };
  }

  redirect("/dashboard");
}

export async function registerAction(
  _prev: AuthFormState,
  formData: FormData,
): Promise<AuthFormState> {
  const text = (name: string) => String(formData.get(name) ?? "").trim();

  // Phone is formatted for reading as it is typed; what gets stored is digits.
  const phone = normalizePhone(text("phone"));

  /*
   * Echoed back on a rejected submission so the customer does not refill five
   * steps because a username was taken. The password and the Social Security
   * number are deliberately absent from this object.
   */
  const values = {
    firstName: text("firstName"),
    middleName: text("middleName"),
    lastName: text("lastName"),
    email: text("email"),
    phone: text("phone"),
    username: text("username"),
    dateOfBirth: text("dateOfBirth"),
    streetAddress: text("streetAddress"),
    addressLine2: text("addressLine2"),
    city: text("city"),
    state: text("state"),
    postalCode: text("postalCode"),
  };

  const parsed = registerFormSchema.safeParse({
    username: text("username"),
    email: text("email"),
    password: String(formData.get("password") ?? ""),
    confirmPassword: String(formData.get("confirmPassword") ?? ""),
    firstName: text("firstName"),
    middleName: text("middleName") || undefined,
    lastName: text("lastName"),
    dateOfBirth: text("dateOfBirth"),
    phone,
    streetAddress: text("streetAddress"),
    addressLine2: text("addressLine2") || undefined,
    city: text("city"),
    state: text("state"),
    postalCode: text("postalCode"),
    ssn: text("ssn"),
    acceptedTerms: formData.get("acceptedTerms") ? "on" : undefined,
  });

  if (!parsed.success) return { fields: fieldErrors(parsed.error), values };

  try {
    /*
     * confirmPassword and acceptedTerms are browser-side concerns and are
     * dropped here so neither reaches the gateway. The Social Security number
     * does go, once: the server checks its shape, keeps the last four digits
     * and discards the rest. It is not written to this module's state, not
     * logged, and not returned.
     */
    const {
      confirmPassword: _confirmPassword,
      acceptedTerms: _acceptedTerms,
      ...registration
    } = parsed.data;

    const auth = await apiRegister({
      ...registration,
      phone,
      state: registration.state.toUpperCase(),
      ssn: registration.ssn.replace(/\D/g, ""),
    });

    if (!auth.token) {
      return { error: "Account created, but no session was issued. Please sign in." };
    }
    await setSessionCookie(auth.token, auth.expiresIn);

    /*
     * No redirect. The wizard has one more step to show — what was submitted,
     * what happens next, and where to go from here — and a redirect would
     * replace that with a dashboard the customer did not ask for yet.
     */
    return { completed: true, username: registration.username };
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.status === 409) {
        return {
          error: error.userMessage || "That username or email is already registered.",
          values,
        };
      }
      return { error: error.userMessage, values };
    }
    if (error instanceof NetworkError) return { error: error.userMessage, values };
    return { error: "Could not create your account. Please try again.", values };
  }
}

export async function logoutAction(): Promise<void> {
  await clearSessionCookie();
  redirect("/login");
}
