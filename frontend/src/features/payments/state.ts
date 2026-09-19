/**
 * The shape the payee form's server action hands back.
 *
 * Declared outside the `"use server"` module for the same reason as the money
 * forms': that file may export async functions and nothing else.
 */
export type BeneficiaryFormState =
  | { status: "idle" }
  | { status: "invalid"; fields: Record<string, string> }
  | { status: "failed"; error: string }
  | { status: "saved"; name: string; maskedNumber: string };

export const BENEFICIARY_IDLE: BeneficiaryFormState = { status: "idle" };
