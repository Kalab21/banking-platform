import { describe, expect, it } from "vitest";
import {
  depositSchema,
  fieldErrors,
  loginSchema,
  registerSchema,
  transferSchema,
  twoFactorCodeSchema,
} from "@/lib/validation";

describe("loginSchema", () => {
  it("accepts a filled-in form", () => {
    expect(loginSchema.safeParse({ username: "demo", password: "Password123!" }).success).toBe(true);
  });

  it("rejects an empty username", () => {
    const result = loginSchema.safeParse({ username: "  ", password: "x" });
    expect(result.success).toBe(false);
    if (!result.success) expect(fieldErrors(result.error).username).toBe("Enter your username");
  });
});

describe("registerSchema", () => {
  it("rejects a password under eight characters", () => {
    const result = registerSchema.safeParse({
      username: "demo",
      email: "demo@example.com",
      password: "short",
      firstName: "Demo",
      lastName: "User",
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(fieldErrors(result.error).password).toBe("Password must be at least 8 characters");
    }
  });

  it("rejects an invalid email address", () => {
    const result = registerSchema.safeParse({
      username: "demo",
      email: "not-an-email",
      password: "Password123!",
      firstName: "Demo",
      lastName: "User",
    });
    expect(result.success).toBe(false);
    if (!result.success) expect(fieldErrors(result.error).email).toContain("valid email");
  });
});

describe("money validation", () => {
  const base = { accountId: "1", description: undefined };

  it("accepts a normal amount", () => {
    expect(depositSchema.safeParse({ ...base, amount: "250.00" }).success).toBe(true);
  });

  it("rejects zero and negative amounts", () => {
    expect(depositSchema.safeParse({ ...base, amount: "0" }).success).toBe(false);
    expect(depositSchema.safeParse({ ...base, amount: "-10" }).success).toBe(false);
  });

  it("rejects more than two decimal places, which the backend would round", () => {
    expect(depositSchema.safeParse({ ...base, amount: "10.999" }).success).toBe(false);
  });

  it("rejects text in the amount field", () => {
    expect(depositSchema.safeParse({ ...base, amount: "one hundred" }).success).toBe(false);
  });

  it("requires an account to be chosen", () => {
    const result = depositSchema.safeParse({ accountId: "", amount: "10.00" });
    expect(result.success).toBe(false);
    if (!result.success) expect(fieldErrors(result.error).accountId).toBe("Choose an account");
  });
});

describe("transferSchema", () => {
  it("accepts a transfer between two different accounts", () => {
    const result = transferSchema.safeParse({
      fromAccountId: "1",
      toAccountId: "2",
      amount: "100.00",
    });
    expect(result.success).toBe(true);
  });

  it("refuses a transfer to the same account, matching the backend rule", () => {
    const result = transferSchema.safeParse({
      fromAccountId: "1",
      toAccountId: "1",
      amount: "100.00",
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(fieldErrors(result.error).toAccountId).toBe("Choose two different accounts");
    }
  });
});

describe("twoFactorCodeSchema", () => {
  it("accepts a six-digit code", () => {
    expect(twoFactorCodeSchema.safeParse({ code: "123456" }).success).toBe(true);
  });

  it("rejects anything that is not six digits", () => {
    expect(twoFactorCodeSchema.safeParse({ code: "12345" }).success).toBe(false);
    expect(twoFactorCodeSchema.safeParse({ code: "abcdef" }).success).toBe(false);
  });
});

describe("fieldErrors", () => {
  it("keeps only the first message per field", () => {
    const result = registerSchema.safeParse({
      username: "",
      email: "bad",
      password: "",
      firstName: "",
      lastName: "",
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      const errors = fieldErrors(result.error);
      expect(Object.values(errors).every((m) => typeof m === "string")).toBe(true);
      expect(errors.username).toBeDefined();
    }
  });
});
