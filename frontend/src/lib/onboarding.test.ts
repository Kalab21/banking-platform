import { describe, expect, it } from "vitest";
import { formatSsn, lastFourOfSsn, maskedSsn, normalizeSsn } from "@/lib/ssn";
import { isUsStateCode, stateName, US_STATES } from "@/lib/us-states";
import {
  accountStepSchema,
  addressStepSchema,
  ageOn,
  fieldErrors,
  identityStepSchema,
  personalStepSchema,
  registerFormSchema,
} from "@/lib/validation";
import { STEPS, stepForField } from "@/features/auth/onboarding/steps";

/**
 * The onboarding rules, as the browser applies them.
 *
 * These are the same constraints the backend enforces on RegisterRequest. The
 * backend is the one that counts; this is here so the customer finds out where
 * they are typing rather than after a round trip, and so the two cannot drift
 * apart unnoticed.
 *
 * Values are synthetic throughout: example.com, the 555-01xx range reserved for
 * fiction, and a Social Security number reserved for demonstration use.
 */

const VALID = {
  username: "avery.sinclair",
  email: "avery.sinclair@example.com",
  password: "Northbank2026",
  confirmPassword: "Northbank2026",
  firstName: "Avery",
  middleName: "Quinn",
  lastName: "Sinclair",
  dateOfBirth: "1990-01-15",
  phone: "2405550148",
  streetAddress: "123 Example Street",
  addressLine2: "Apt 4B",
  city: "Silver Spring",
  state: "MD",
  postalCode: "20910",
  ssn: "123456789",
  acceptedTerms: "on",
};

function errorFor(schema: { safeParse: (v: unknown) => unknown }, value: object, field: string) {
  const result = schema.safeParse(value) as
    | { success: true }
    | { success: false; error: Parameters<typeof fieldErrors>[0] };
  if (result.success) return undefined;
  return fieldErrors(result.error)[field];
}

function isoDaysFromNow(days: number): string {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function isoYearsAgo(years: number, offsetDays = 0): string {
  const date = new Date();
  date.setUTCFullYear(date.getUTCFullYear() - years);
  date.setUTCDate(date.getUTCDate() + offsetDays);
  return date.toISOString().slice(0, 10);
}

describe("accountStepSchema", () => {
  const step = {
    username: VALID.username,
    email: VALID.email,
    password: VALID.password,
    confirmPassword: VALID.confirmPassword,
  };

  it("accepts a complete sign-in", () => {
    expect(accountStepSchema.safeParse(step).success).toBe(true);
  });

  it("rejects a password that does not match its confirmation", () => {
    expect(errorFor(accountStepSchema, { ...step, confirmPassword: "Northbank2027" }, "confirmPassword")).toBe(
      "Passwords do not match",
    );
  });

  it("keeps the password rules the backend enforces", () => {
    expect(errorFor(accountStepSchema, { ...step, password: "short", confirmPassword: "short" }, "password")).toContain(
      "at least 8",
    );
    expect(
      errorFor(accountStepSchema, { ...step, password: "northbank2026", confirmPassword: "northbank2026" }, "password"),
    ).toContain("uppercase");
  });
});

describe("ageOn", () => {
  it("counts whole years, so a birthday today counts", () => {
    expect(ageOn("2000-06-15", new Date(Date.UTC(2026, 5, 15)))).toBe(26);
    expect(ageOn("2000-06-15", new Date(Date.UTC(2026, 5, 14)))).toBe(25);
  });

  it("rejects a date that is not a date", () => {
    // February 31st would otherwise roll forward into March and be accepted.
    expect(ageOn("1990-02-31")).toBeNull();
    expect(ageOn("not-a-date")).toBeNull();
    expect(ageOn("15-01-1990")).toBeNull();
  });
});

describe("personalStepSchema", () => {
  const step = {
    firstName: VALID.firstName,
    middleName: VALID.middleName,
    lastName: VALID.lastName,
    dateOfBirth: VALID.dateOfBirth,
    phone: VALID.phone,
  };

  it("accepts a complete personal step", () => {
    expect(personalStepSchema.safeParse(step).success).toBe(true);
  });

  it("does not require a middle name", () => {
    expect(personalStepSchema.safeParse({ ...step, middleName: "" }).success).toBe(true);
  });

  it("rejects someone under eighteen", () => {
    expect(errorFor(personalStepSchema, { ...step, dateOfBirth: isoYearsAgo(18, 1) }, "dateOfBirth")).toContain("18");
  });

  it("accepts someone who turned eighteen today", () => {
    expect(personalStepSchema.safeParse({ ...step, dateOfBirth: isoYearsAgo(18) }).success).toBe(true);
  });

  it("rejects a date of birth in the future", () => {
    expect(errorFor(personalStepSchema, { ...step, dateOfBirth: isoDaysFromNow(1) }, "dateOfBirth")).toContain(
      "future",
    );
  });

  it("requires ten digits of phone number, however they are punctuated", () => {
    expect(personalStepSchema.safeParse({ ...step, phone: "(240) 555-0148" }).success).toBe(true);
    expect(errorFor(personalStepSchema, { ...step, phone: "240555014" }, "phone")).toContain("10-digit");
  });

  it("accepts names that a character allowlist would reject", () => {
    for (const name of ["O'Neill", "Al-Rashid", "Ní Bhriain", "María José", "李"]) {
      expect(personalStepSchema.safeParse({ ...step, firstName: name, lastName: name }).success).toBe(true);
    }
  });
});

describe("addressStepSchema", () => {
  const step = {
    streetAddress: VALID.streetAddress,
    addressLine2: VALID.addressLine2,
    city: VALID.city,
    state: VALID.state,
    postalCode: VALID.postalCode,
  };

  it("accepts a complete address", () => {
    expect(addressStepSchema.safeParse(step).success).toBe(true);
  });

  it("does not require an apartment line", () => {
    expect(addressStepSchema.safeParse({ ...step, addressLine2: "" }).success).toBe(true);
  });

  it("requires a real state code", () => {
    expect(errorFor(addressStepSchema, { ...step, state: "" }, "state")).toBe("Select a state");
    expect(errorFor(addressStepSchema, { ...step, state: "ZZ" }, "state")).toBe("Select a state");
  });

  it("accepts five-digit and nine-digit ZIP codes and nothing else", () => {
    expect(addressStepSchema.safeParse({ ...step, postalCode: "20910" }).success).toBe(true);
    expect(addressStepSchema.safeParse({ ...step, postalCode: "20910-1234" }).success).toBe(true);
    expect(errorFor(addressStepSchema, { ...step, postalCode: "209" }, "postalCode")).toContain("5-digit");
    expect(errorFor(addressStepSchema, { ...step, postalCode: "ABCDE" }, "postalCode")).toContain("5-digit");
  });
});

describe("identityStepSchema", () => {
  it("accepts nine digits with or without hyphens", () => {
    expect(identityStepSchema.safeParse({ ssn: "123456789", acceptedTerms: "on" }).success).toBe(true);
    expect(identityStepSchema.safeParse({ ssn: "123-45-6789", acceptedTerms: "on" }).success).toBe(true);
  });

  it("rejects anything that is not nine digits", () => {
    expect(errorFor(identityStepSchema, { ssn: "12345678", acceptedTerms: "on" }, "ssn")).toContain("9-digit");
  });

  it("requires the terms to be accepted", () => {
    expect(errorFor(identityStepSchema, { ssn: "123456789" }, "acceptedTerms")).toBe(
      "Accept the terms to continue",
    );
  });
});

describe("registerFormSchema", () => {
  it("accepts every step combined", () => {
    expect(registerFormSchema.safeParse(VALID).success).toBe(true);
  });

  it("rejects a submission missing a step's worth of fields", () => {
    const { streetAddress: _street, city: _city, ...withoutAddress } = VALID;
    const result = registerFormSchema.safeParse(withoutAddress);

    expect(result.success).toBe(false);
    if (!result.success) {
      const errors = fieldErrors(result.error);
      expect(Object.keys(errors)).toContain("streetAddress");
      expect(Object.keys(errors)).toContain("city");
    }
  });
});

describe("the wizard's shape", () => {
  it("runs account, personal, address, identity, review", () => {
    expect(STEPS.map((step) => step.id)).toEqual([
      "account",
      "personal",
      "address",
      "identity",
      "review",
    ]);
  });

  it("knows which step owns each field, so a server rejection lands somewhere useful", () => {
    expect(stepForField("username")).toBe("account");
    expect(stepForField("dateOfBirth")).toBe("personal");
    expect(stepForField("postalCode")).toBe("address");
    expect(stepForField("ssn")).toBe("identity");
    expect(stepForField("nothingLikeThis")).toBeNull();
  });

  it("gives every field of the request a step it can be corrected on", () => {
    const owned = new Set(STEPS.flatMap((step) => step.fields as string[]));
    for (const field of Object.keys(VALID)) {
      expect(owned, `${field} has no step`).toContain(field);
    }
  });
});

describe("Social Security number handling", () => {
  it("keeps only digits, and no more than nine", () => {
    expect(normalizeSsn("123-45-6789")).toBe("123456789");
    expect(normalizeSsn("123 45 6789 000")).toBe("123456789");
  });

  it("groups progressively as it is typed", () => {
    expect(formatSsn("12")).toBe("12");
    expect(formatSsn("1234")).toBe("123-4");
    expect(formatSsn("123456789")).toBe("123-45-6789");
  });

  it("masks to the four digits the server will keep", () => {
    // The mask is built from four digits, not from a full number. There is no
    // full number to build it from once the request has been made.
    expect(lastFourOfSsn("123-45-6789")).toBe("6789");
    expect(maskedSsn("6789")).toBe("•••-••-6789");
    expect(maskedSsn(null)).toBe("");
  });

  it("never renders more than four digits of a number", () => {
    const masked = maskedSsn(lastFourOfSsn("123-45-6789"));
    expect(masked).not.toContain("123");
    expect(masked).not.toContain("45");
  });
});

describe("US states", () => {
  it("covers the fifty states plus DC and Puerto Rico", () => {
    expect(US_STATES).toHaveLength(52);
  });

  it("recognises a code whatever case it arrives in", () => {
    expect(isUsStateCode("md")).toBe(true);
    expect(isUsStateCode("MD")).toBe(true);
    expect(isUsStateCode("ZZ")).toBe(false);
  });

  it("names a state for the review screen", () => {
    expect(stateName("MD")).toBe("Maryland");
    expect(stateName(null)).toBe("");
  });
});
