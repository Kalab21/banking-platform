import type { ZodType } from "zod";
import {
  accountStepSchema,
  addressStepSchema,
  identityStepSchema,
  personalStepSchema,
} from "@/lib/validation";

/**
 * The onboarding wizard, as data.
 *
 * The stepper, the per-step validation and the "which step does this error
 * belong to" lookup all read this array, so they cannot disagree about what the
 * steps are or what is on them.
 */

export type StepId = "account" | "personal" | "address" | "identity" | "review";

export interface OnboardingData {
  username: string;
  email: string;
  password: string;
  confirmPassword: string;
  firstName: string;
  middleName: string;
  lastName: string;
  dateOfBirth: string;
  phone: string;
  streetAddress: string;
  addressLine2: string;
  city: string;
  state: string;
  postalCode: string;
  ssn: string;
  acceptedTerms: boolean;
}

export const EMPTY_ONBOARDING: OnboardingData = {
  username: "",
  email: "",
  password: "",
  confirmPassword: "",
  firstName: "",
  middleName: "",
  lastName: "",
  dateOfBirth: "",
  phone: "",
  streetAddress: "",
  addressLine2: "",
  city: "",
  state: "",
  postalCode: "",
  ssn: "",
  acceptedTerms: false,
};

export interface Step {
  id: StepId;
  /** Shown in the stepper. Short, because five of these sit side by side. */
  label: string;
  /** The heading on the step itself. */
  title: string;
  description: string;
  fields: (keyof OnboardingData)[];
  /** Validated when the customer moves forward. Review has nothing of its own. */
  schema?: ZodType;
}

export const STEPS: Step[] = [
  {
    id: "account",
    label: "Account",
    title: "Create your sign-in",
    description: "How you will get into your account.",
    fields: ["username", "email", "password", "confirmPassword"],
    schema: accountStepSchema,
  },
  {
    id: "personal",
    label: "Personal",
    title: "About you",
    description: "Your legal name as it appears on your government-issued ID.",
    fields: ["firstName", "middleName", "lastName", "dateOfBirth", "phone"],
    schema: personalStepSchema,
  },
  {
    id: "address",
    label: "Address",
    title: "Where you live",
    description: "A US residential address. A PO box cannot be used on its own.",
    fields: ["streetAddress", "addressLine2", "city", "state", "postalCode"],
    schema: addressStepSchema,
  },
  {
    id: "identity",
    label: "Identity",
    title: "Identity details",
    description: "Required to open a US bank account.",
    fields: ["ssn", "acceptedTerms"],
    schema: identityStepSchema,
  },
  {
    id: "review",
    label: "Review",
    title: "Check your details",
    description: "Change anything that is not right before you submit.",
    fields: [],
  },
];

export function stepIndex(id: StepId): number {
  return STEPS.findIndex((step) => step.id === id);
}

/**
 * Which step owns a field, so a rejection from the server can send the customer
 * to the step that can fix it rather than leaving them on the review screen
 * with a message about a field they cannot see.
 */
export function stepForField(field: string): StepId | null {
  const owner = STEPS.find((step) => (step.fields as string[]).includes(field));
  return owner?.id ?? null;
}
