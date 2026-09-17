import { describe, expect, it } from "vitest";
import { formatPhone, normalizePhone } from "@/lib/phone";

/**
 * Phone entry.
 *
 * What the customer reads and what gets stored are deliberately different: the
 * field shows a formatted US number, and the request carries digits, so the
 * stored value is not a presentation string that later has to be parsed back.
 */

describe("formatPhone", () => {
  it("formats a complete ten-digit number", () => {
    expect(formatPhone("2402881031")).toBe("(240) 288-1031");
  });

  it("formats progressively as the number is typed", () => {
    expect(formatPhone("2")).toBe("(2");
    expect(formatPhone("240")).toBe("(240");
    expect(formatPhone("2402")).toBe("(240) 2");
    expect(formatPhone("240288")).toBe("(240) 288");
    expect(formatPhone("2402881")).toBe("(240) 288-1");
  });

  it("ignores punctuation the customer pastes in", () => {
    expect(formatPhone("240-288-1031")).toBe("(240) 288-1031");
    expect(formatPhone("240.288.1031")).toBe("(240) 288-1031");
  });

  it("treats a pasted +1 country code as the same US number", () => {
    expect(formatPhone("+1 (240) 288-1031")).toBe("(240) 288-1031");
    expect(formatPhone("12402881031")).toBe("(240) 288-1031");
  });

  it("leaves a longer number unformatted rather than forcing it into a US shape", () => {
    // Not a US national number, so no parentheses are invented for it.
    expect(formatPhone("44207946095812")).toBe("44207946095812");
  });

  it("returns an empty string for empty input", () => {
    expect(formatPhone("")).toBe("");
    expect(formatPhone("   ")).toBe("");
  });
});

describe("normalizePhone", () => {
  it("reduces a formatted number to the digits that get stored", () => {
    expect(normalizePhone("(240) 288-1031")).toBe("2402881031");
  });

  it("is idempotent", () => {
    expect(normalizePhone(normalizePhone("(240) 288-1031"))).toBe("2402881031");
  });

  it("bounds the length so the backend's column limit cannot be exceeded", () => {
    expect(normalizePhone("1".repeat(40))).toHaveLength(15);
  });

  it("returns an empty string when nothing was entered", () => {
    expect(normalizePhone("")).toBe("");
  });
});
