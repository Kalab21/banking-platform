"use server";

import { revalidatePath } from "next/cache";
import { disableTwoFactor, setupTwoFactor, verifyTwoFactor } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { requireSession } from "@/lib/session";
import { fieldErrors, twoFactorCodeSchema } from "@/lib/validation";

/**
 * Two-factor enrolment.
 *
 * Enrolment here is the first half of a second factor that sign-in enforces.
 * `POST /api/auth/login` checks `twoFactorEnabled`, and for an enrolled account
 * a correct password alone answers `twoFactorRequired` and issues no token; the
 * session begins only after a valid TOTP code. Disabling 2FA removes the secret
 * and the challenge with it.
 */

export interface TwoFactorState {
  error?: string;
  success?: string;
  fields?: Record<string, string>;
  setup?: { secret: string; otpauthUri: string };
}

export async function startTwoFactorAction(
  _prev: TwoFactorState,
): Promise<TwoFactorState> {
  const session = await requireSession();

  try {
    const setup = await setupTwoFactor(session.userId);
    return { setup: { secret: setup.secret, otpauthUri: setup.otpauthUri } };
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return { error: error.userMessage };
    }
    return { error: "Two-factor setup could not be started." };
  }
}

export async function confirmTwoFactorAction(
  prev: TwoFactorState,
  formData: FormData,
): Promise<TwoFactorState> {
  const session = await requireSession();

  const parsed = twoFactorCodeSchema.safeParse({ code: formData.get("code") });
  if (!parsed.success) return { ...prev, fields: fieldErrors(parsed.error) };

  try {
    await verifyTwoFactor(session.userId, parsed.data.code);
    revalidatePath("/profile");
    revalidatePath("/dashboard");
    return { success: "Two-factor authentication is now enabled on your account." };
  } catch (error) {
    if (error instanceof ApiError) {
      return { ...prev, error: error.userMessage || "That code was not accepted." };
    }
    if (error instanceof NetworkError) return { ...prev, error: error.userMessage };
    return { ...prev, error: "That code could not be verified." };
  }
}

export async function disableTwoFactorAction(
  _prev: TwoFactorState,
  formData: FormData,
): Promise<TwoFactorState> {
  const session = await requireSession();

  const parsed = twoFactorCodeSchema.safeParse({ code: formData.get("code") });
  if (!parsed.success) return { fields: fieldErrors(parsed.error) };

  try {
    await disableTwoFactor(session.userId, parsed.data.code);
    revalidatePath("/profile");
    revalidatePath("/dashboard");
    return { success: "Two-factor authentication has been turned off." };
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: error.userMessage || "That code was not accepted." };
    }
    if (error instanceof NetworkError) return { error: error.userMessage };
    return { error: "Two-factor could not be disabled." };
  }
}
