import { expect, type Page } from "@playwright/test";

/**
 * Driving the onboarding wizard from a test.
 *
 * Shared by the offline and live suites so that the two cannot disagree about
 * what the flow is. Every value is synthetic: `example.com`, the `555-01xx`
 * range reserved for fiction, and a Social Security number reserved for
 * demonstration use.
 */

export interface OnboardingIdentity {
  username: string;
  email: string;
  password: string;
  firstName: string;
  middleName: string;
  lastName: string;
  dateOfBirth: string;
  phone: string;
  formattedPhone: string;
  streetAddress: string;
  addressLine2: string;
  city: string;
  state: string;
  stateName: string;
  postalCode: string;
  ssn: string;
  ssnLast4: string;
}

/** A complete applicant, unique per run so repeated runs do not collide. */
export function syntheticApplicant(overrides: Partial<OnboardingIdentity> = {}): OnboardingIdentity {
  const suffix = Date.now().toString(36);
  return {
    username: `avery.${suffix}`,
    email: `avery.sinclair.${suffix}@example.com`,
    password: "Northbank2026",
    firstName: "Avery",
    middleName: "Quinn",
    lastName: "Sinclair",
    dateOfBirth: "1990-01-15",
    phone: "2405550148",
    formattedPhone: "(240) 555-0148",
    streetAddress: "123 Example Street",
    addressLine2: "Apt 4B",
    city: "Silver Spring",
    state: "MD",
    stateName: "Maryland",
    ssn: "123456789",
    ssnLast4: "6789",
    postalCode: "20910",
    ...overrides,
  };
}

export async function fillAccountStep(page: Page, who: OnboardingIdentity): Promise<void> {
  await page.getByLabel("Username").fill(who.username);
  await page.getByLabel("Email address").fill(who.email);
  await page.getByLabel("Password", { exact: true }).fill(who.password);
  await page.getByLabel("Confirm password").fill(who.password);
}

export async function fillPersonalStep(page: Page, who: OnboardingIdentity): Promise<void> {
  await page.getByLabel("First name").fill(who.firstName);
  await page.getByLabel("Middle name").fill(who.middleName);
  await page.getByLabel("Last name").fill(who.lastName);
  await page.getByLabel("Date of birth").fill(who.dateOfBirth);
  await page.getByLabel("Phone number").fill(who.phone);
}

export async function fillAddressStep(page: Page, who: OnboardingIdentity): Promise<void> {
  await page.getByLabel("Street address").fill(who.streetAddress);
  await page.getByLabel("Apartment, suite or unit").fill(who.addressLine2);
  await page.getByLabel("City").fill(who.city);
  await page.getByLabel("State").selectOption(who.state);
  await page.getByLabel("ZIP code").fill(who.postalCode);
}

export async function fillIdentityStep(page: Page, who: OnboardingIdentity): Promise<void> {
  await page.getByLabel("Social Security number", { exact: true }).fill(who.ssn);
  await page.getByRole("checkbox").check();
}

const next = (page: Page) => page.getByRole("button", { name: /continue|back to review/i }).click();

/** Walks every step and stops on the review screen, without submitting. */
export async function completeWizardToReview(
  page: Page,
  who: OnboardingIdentity,
): Promise<void> {
  await fillAccountStep(page, who);
  await next(page);
  await expect(page.getByRole("heading", { name: "About you" })).toBeVisible();

  await fillPersonalStep(page, who);
  await next(page);
  await expect(page.getByRole("heading", { name: "Where you live" })).toBeVisible();

  await fillAddressStep(page, who);
  await next(page);
  await expect(page.getByRole("heading", { name: "Identity details" })).toBeVisible();

  await fillIdentityStep(page, who);
  await next(page);
  await expect(page.getByRole("heading", { name: "Check your details" })).toBeVisible();
}
