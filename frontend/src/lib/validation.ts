import { z } from "zod";
import { isUsStateCode } from "@/lib/us-states";

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

/**
 * The registration password rules, in one place.
 *
 * These are the same three conditions the backend enforces on RegisterRequest,
 * and the same three the console renders as a live checklist. Keeping them as
 * data rather than as prose means the checklist cannot drift from the schema:
 * both read this array.
 */
export const PASSWORD_RULES: { id: string; label: string; test: (value: string) => boolean }[] = [
  { id: "length", label: "At least 8 characters", test: (v) => v.length >= 8 },
  { id: "upper", label: "One uppercase letter", test: (v) => /[A-Z]/.test(v) },
  { id: "lower", label: "One lowercase letter", test: (v) => /[a-z]/.test(v) },
  { id: "number", label: "One number", test: (v) => /[0-9]/.test(v) },
];

/** True when every rule the backend enforces is satisfied. */
export function passwordMeetsPolicy(value: string): boolean {
  return PASSWORD_RULES.every((rule) => rule.test(value));
}

const password = z
  .string()
  .min(8, "Password must be at least 8 characters")
  .refine(
    (v) => /[A-Z]/.test(v) && /[a-z]/.test(v) && /[0-9]/.test(v),
    "Password must include an uppercase letter, a lowercase letter and a number",
  );

/**
 * Names are trimmed and bounded, and nothing more.
 *
 * No character allowlist: apostrophes, hyphens, spaces and non-Latin scripts
 * are all ordinary parts of real names, and a regex that "looks reasonable"
 * mostly succeeds at rejecting people.
 */
const personName = (field: string) =>
  z
    .string()
    .trim()
    .min(1, `Enter your ${field}`)
    .max(50, `${field[0].toUpperCase()}${field.slice(1)} must be 50 characters or fewer`);

/**
 * Onboarding is a wizard, so its schema is assembled from one schema per step.
 *
 * Each step validates on its own when the customer moves forward, and the
 * combined schema validates again at submit. Splitting them this way means a
 * step cannot be advanced past with a field the final request would reject, and
 * there is still only one definition of each rule.
 */

export const accountStepSchema = z
  .object({
    username: z
      .string()
      .trim()
      .min(3, "Username must be at least 3 characters")
      .max(50, "Username must be 50 characters or fewer"),
    email: z.string().trim().email("Enter a valid email address"),
    password,
    confirmPassword: z.string().min(1, "Re-enter your password"),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: "Passwords do not match",
    path: ["confirmPassword"],
  });

/**
 * Old enough to hold an account, and plausibly alive.
 *
 * Both bounds are the ones the backend enforces on RegisterRequest, expressed
 * against whole years rather than days so that a birthday today counts.
 */
const MINIMUM_AGE = 18;
const MAXIMUM_AGE = 120;

export function ageOn(dateOfBirth: string, today = new Date()): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateOfBirth.trim());
  if (!match) return null;

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const born = new Date(Date.UTC(year, month - 1, day));
  // Round-tripping catches 31 February and friends, which Date would otherwise
  // roll forward into March.
  if (
    born.getUTCFullYear() !== year ||
    born.getUTCMonth() !== month - 1 ||
    born.getUTCDate() !== day
  ) {
    return null;
  }

  let age = today.getUTCFullYear() - year;
  const hadBirthday =
    today.getUTCMonth() > month - 1 ||
    (today.getUTCMonth() === month - 1 && today.getUTCDate() >= day);
  if (!hadBirthday) age -= 1;
  return age;
}

const dateOfBirth = z
  .string()
  .trim()
  .min(1, "Enter your date of birth")
  .superRefine((value, ctx) => {
    const age = ageOn(value);
    if (age === null) {
      ctx.addIssue({ code: "custom", message: "Enter a valid date of birth" });
      return;
    }
    if (age < 0) {
      ctx.addIssue({ code: "custom", message: "Date of birth cannot be in the future" });
      return;
    }
    if (age < MINIMUM_AGE) {
      ctx.addIssue({
        code: "custom",
        message: `You must be at least ${MINIMUM_AGE} to open an account`,
      });
      return;
    }
    if (age > MAXIMUM_AGE) {
      ctx.addIssue({ code: "custom", message: "Enter a valid date of birth" });
    }
  });

/**
 * Ten digits after formatting is stripped.
 *
 * The field shows `(240) 555-0148` while it is typed; this validates what will
 * actually be sent, which is the digits.
 */
const phone = z
  .string()
  .trim()
  .min(1, "Enter your phone number")
  .refine((v) => v.replace(/\D/g, "").length === 10, "Enter a 10-digit US phone number");

export const personalStepSchema = z.object({
  firstName: personName("first name"),
  middleName: z.string().trim().max(50, "Middle name must be 50 characters or fewer").optional(),
  lastName: personName("last name"),
  dateOfBirth,
  phone,
});

export const addressStepSchema = z.object({
  streetAddress: z
    .string()
    .trim()
    .min(1, "Enter your street address")
    .max(120, "Street address must be 120 characters or fewer"),
  addressLine2: z
    .string()
    .trim()
    .max(60, "Apartment or unit must be 60 characters or fewer")
    .optional(),
  city: z.string().trim().min(1, "Enter your city").max(60, "City must be 60 characters or fewer"),
  state: z
    .string()
    .trim()
    .min(1, "Select a state")
    .refine((v) => isUsStateCode(v), "Select a state"),
  postalCode: z
    .string()
    .trim()
    .min(1, "Enter your ZIP code")
    .regex(/^\d{5}(-\d{4})?$/, "Enter a valid 5-digit ZIP code"),
});

/**
 * The identity step.
 *
 * Format only. Nothing here verifies that the number belongs to anyone — there
 * is no verification provider behind this system — so the copy around this
 * field says the details were submitted, never that an identity was verified.
 */
export const identityStepSchema = z.object({
  ssn: z
    .string()
    .trim()
    .min(1, "Enter your Social Security number")
    .refine(
      (v) => /^\d{9}$/.test(v.replace(/\D/g, "")),
      "Enter a valid 9-digit Social Security number",
    ),
  /*
   * The checkbox, as a form sends it: "on" when ticked and nothing at all when
   * not. Declared as a required string with its own message rather than an
   * optional one, so an absent value and an unticked box produce the same
   * complaint instead of an absent value quietly passing.
   */
  acceptedTerms: z
    .string({ error: "Accept the terms to continue" })
    .refine((v) => v === "on", "Accept the terms to continue"),
});

/** What the registration request carries, which is every step combined. */
export const registerSchema = z.object({
  username: z
    .string()
    .trim()
    .min(3, "Username must be at least 3 characters")
    .max(50, "Username must be 50 characters or fewer"),
  email: z.string().trim().email("Enter a valid email address"),
  password,
  firstName: personName("first name"),
  middleName: z.string().trim().max(50, "Middle name must be 50 characters or fewer").optional(),
  lastName: personName("last name"),
  dateOfBirth,
  phone,
  streetAddress: addressStepSchema.shape.streetAddress,
  addressLine2: addressStepSchema.shape.addressLine2,
  city: addressStepSchema.shape.city,
  state: addressStepSchema.shape.state,
  postalCode: addressStepSchema.shape.postalCode,
  ssn: identityStepSchema.shape.ssn,
});

/**
 * What the browser form validates, which is the API contract plus the two
 * fields that exist only in the browser.
 *
 * `confirmPassword` catches a typo before an account is created and
 * `acceptedTerms` is a consent checkbox. Neither is sent to the gateway and
 * neither is stored — `registerSchema` is what the request is built from.
 */
export const registerFormSchema = registerSchema
  .extend({
    confirmPassword: z.string().min(1, "Re-enter your password"),
    acceptedTerms: identityStepSchema.shape.acceptedTerms,
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: "Passwords do not match",
    path: ["confirmPassword"],
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
export type RegisterFormInput = z.infer<typeof registerFormSchema>;
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

/**
 * A saved payee.
 *
 * `userId` is deliberately absent: it comes from the session on the server, so
 * the browser cannot name whose profile the payee is saved against. The account
 * number is required here even though the backend accepts it as optional — a
 * payee with no account number is not a payee anyone can pay.
 */
export const beneficiarySchema = z.object({
  name: z.string().trim().min(1, "Enter the payee's name").max(100, "That name is too long"),
  nickname: z.string().trim().max(60, "That nickname is too long").optional(),
  accountNumber: z
    .string()
    .trim()
    .min(4, "Enter the account number")
    .max(34, "That account number is too long")
    .regex(/^[A-Za-z0-9-]+$/, "Use letters, numbers and hyphens only"),
  bankName: z.string().trim().max(100, "That bank name is too long").optional(),
  routingNumber: z
    .string()
    .trim()
    .regex(/^\d{9}$/, "A routing number is nine digits")
    .optional()
    .or(z.literal("")),
  beneficiaryType: z.enum(["INTERNAL", "EXTERNAL_ACH", "WIRE", "SWIFT"], {
    message: "Choose how this payee is paid",
  }),
  currency: z.string().trim().length(3, "Use a three-letter currency code"),
});

export type BeneficiaryInput = z.infer<typeof beneficiarySchema>;
