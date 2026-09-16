import { z } from "zod";

/**
 * Form schemas.
 *
 * These mirror the Jakarta Bean Validation constraints on the backend request
 * DTOs, so the browser rejects what the server would reject. They are a
 * usability layer only — the backend re-validates everything it receives.
 */

/** Money must be a positive amount with at most two decimal places. */
const money = z
  .string()
  .trim()
  .min(1, "Enter an amount")
  .refine((v) => /^\d+(\.\d{1,2})?$/.test(v), "Enter a valid amount, for example 250.00")
  .refine((v) => Number(v) > 0, "Amount must be greater than zero")
  .refine((v) => Number(v) <= 1_000_000, "Amount must be 1,000,000 or less");

export const loginSchema = z.object({
  username: z.string().trim().min(1, "Enter your username"),
  password: z.string().min(1, "Enter your password"),
});

export const registerSchema = z.object({
  username: z
    .string()
    .trim()
    .min(3, "Username must be at least 3 characters")
    .max(50, "Username must be 50 characters or fewer"),
  email: z.string().trim().email("Enter a valid email address"),
  password: z.string().min(8, "Password must be at least 8 characters"),
  firstName: z.string().trim().min(1, "Enter your first name"),
  lastName: z.string().trim().min(1, "Enter your last name"),
  phone: z.string().trim().optional(),
});

export const depositSchema = z.object({
  accountId: z.string().min(1, "Choose an account"),
  amount: money,
  description: z.string().trim().max(255, "Description is too long").optional(),
});

export const withdrawSchema = depositSchema;

export const transferSchema = z
  .object({
    fromAccountId: z.string().min(1, "Choose the account to send from"),
    toAccountId: z.string().min(1, "Choose the account to send to"),
    amount: money,
    description: z.string().trim().max(255, "Description is too long").optional(),
  })
  .refine((data) => data.fromAccountId !== data.toAccountId, {
    message: "Choose two different accounts",
    path: ["toAccountId"],
  });

export const loanRepaymentSchema = z.object({
  amount: money,
  sourceAccountId: z.string().optional(),
});

export const kycDocumentSchema = z.object({
  documentType: z.string().min(1, "Choose a document type"),
  documentRef: z
    .string()
    .trim()
    .min(3, "Enter the document reference")
    .max(100, "Reference is too long"),
});

export const twoFactorCodeSchema = z.object({
  code: z
    .string()
    .trim()
    .regex(/^\d{6}$/, "Enter the 6-digit code from your authenticator app"),
});

export type LoginInput = z.infer<typeof loginSchema>;
export type RegisterInput = z.infer<typeof registerSchema>;
export type TransferInput = z.infer<typeof transferSchema>;

/**
 * Collapses a Zod error into `{ field: message }`.
 *
 * Only the first message per field is kept — showing a stack of complaints
 * under one input is noise, not help.
 */
export function fieldErrors(error: z.ZodError): Record<string, string> {
  const result: Record<string, string> = {};
  for (const issue of error.issues) {
    const key = issue.path.join(".") || "form";
    if (!result[key]) result[key] = issue.message;
  }
  return result;
}
