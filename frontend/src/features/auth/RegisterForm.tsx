"use client";

import { AtSign, ArrowLeft, Phone, ShieldCheck, User } from "lucide-react";
import Link from "next/link";
import { useActionState, useEffect, useRef, useState } from "react";
import { registerAction, type AuthFormState } from "@/features/auth/actions";
import { OnboardingComplete } from "@/features/auth/onboarding/OnboardingComplete";
import { ReviewStep } from "@/features/auth/onboarding/ReviewStep";
import { Stepper } from "@/features/auth/onboarding/Stepper";
import {
  EMPTY_ONBOARDING,
  STEPS,
  stepForField,
  stepIndex,
  type OnboardingData,
  type StepId,
} from "@/features/auth/onboarding/steps";
import {
  Button,
  FieldMessage,
  FormError,
  PasswordField,
  SelectField,
  TextField,
} from "@/components/ui/form";
import { PasswordRequirements } from "@/components/ui/PasswordRequirements";
import { fieldErrors } from "@/lib/validation";
import { formatPhone, normalizePhone } from "@/lib/phone";
import { formatSsn, lastFourOfSsn, normalizeSsn } from "@/lib/ssn";
import { US_STATES } from "@/lib/us-states";

const INITIAL: AuthFormState = {};

/**
 * Opening an account.
 *
 * A wizard rather than one long form, because this collects rather more than a
 * username: a legal name, a date of birth, a residential address and an
 * identity number. Presented as a single column that is a wall of twelve
 * inputs; split into four short steps with a review, each screen asks one
 * coherent question.
 *
 * Every field here is persisted by the backend. None of it is decoration that
 * disappears at submit — the profile is readable afterwards on the profile
 * page, which is the test of whether asking for it was honest.
 *
 * Where the answers live while the wizard is open: in this component's state,
 * in memory. Nothing is written to localStorage, sessionStorage, a cookie, a
 * query parameter or a URL fragment. Leaving the page loses the progress, which
 * is the correct trade — a half-finished onboarding form holds a date of birth,
 * a home address and a Social Security number, and none of that belongs in
 * browser storage waiting for the next person to use the machine.
 */
export function RegisterForm() {
  const [state, action, pending] = useActionState(registerAction, INITIAL);

  const [step, setStep] = useState<StepId>("account");
  const [data, setData] = useState<OnboardingData>(EMPTY_ONBOARDING);
  const [errors, setErrors] = useState<Record<string, string>>({});
  /** Set when a step was opened from review, so Continue goes back there. */
  const [returningToReview, setReturningToReview] = useState(false);

  const headingRef = useRef<HTMLHeadingElement>(null);
  const firstRender = useRef(true);

  const index = stepIndex(step);
  const currentStep = STEPS[index];

  const set = <K extends keyof OnboardingData>(field: K, value: OnboardingData[K]) => {
    setData((previous) => ({ ...previous, [field]: value }));
    // Cleared on edit rather than on blur: the message was about the old value,
    // and leaving it under a field being corrected reads as a fresh complaint
    // about the new one.
    setErrors((previous) => {
      if (!previous[field]) return previous;
      const { [field]: _removed, ...rest } = previous;
      return rest;
    });
  };

  /*
   * A rejection from the server belongs on the step that can fix it. Without
   * this, a duplicate username reported at submit leaves the customer on the
   * review screen reading about a field three steps back.
   *
   * Adjusted during render rather than in an effect. The alternative renders
   * the review screen once with the server's errors invisible, then renders
   * again on the right step — a visible flash of the wrong screen for state
   * that is derived from a value we already have.
   */
  const [handledFields, setHandledFields] = useState<AuthFormState["fields"]>(undefined);
  if (state.fields !== handledFields) {
    setHandledFields(state.fields);
    if (state.fields) {
      setErrors(state.fields);
      const target = Object.keys(state.fields)
        .map(stepForField)
        .filter((id): id is StepId => id !== null)
        .sort((a, b) => stepIndex(a) - stepIndex(b))[0];
      if (target) setStep(target);
    }
  }

  /*
   * Moving between steps replaces the whole panel, which a screen reader has no
   * reason to notice. Focus moves to the new heading so the step is announced
   * and keyboard focus is somewhere sensible rather than back at the top.
   */
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    headingRef.current?.focus();
  }, [step]);

  if (state.completed) {
    return <OnboardingComplete name={data.firstName} ssnLast4={lastFourOfSsn(data.ssn)} />;
  }

  const validateStep = (): boolean => {
    if (!currentStep.schema) return true;

    const subset: Record<string, unknown> = {};
    for (const field of currentStep.fields) {
      const value = data[field];
      if (field === "acceptedTerms") {
        subset[field] = value ? "on" : undefined;
      } else if (field === "phone") {
        subset[field] = normalizePhone(String(value));
      } else if (field === "ssn") {
        subset[field] = normalizeSsn(String(value));
      } else {
        // Empty strings are passed through rather than dropped. A missing key
        // fails as "expected string, received undefined"; an empty string fails
        // the field's own minimum, which is the message worth reading.
        subset[field] = value;
      }
    }

    const result = currentStep.schema.safeParse(subset);
    if (result.success) {
      setErrors({});
      return true;
    }
    setErrors(fieldErrors(result.error));
    return false;
  };

  const goNext = () => {
    if (!validateStep()) return;
    if (returningToReview) {
      setReturningToReview(false);
      setStep("review");
      return;
    }
    setStep(STEPS[Math.min(index + 1, STEPS.length - 1)].id);
  };

  const goBack = () => {
    setErrors({});
    if (returningToReview) {
      setReturningToReview(false);
      setStep("review");
      return;
    }
    setStep(STEPS[Math.max(index - 1, 0)].id);
  };

  const editFromReview = (target: StepId) => {
    setErrors({});
    setReturningToReview(true);
    setStep(target);
  };

  const onReview = step === "review";

  return (
    <form
      action={action}
      className="space-y-6"
      noValidate
      onKeyDown={(event) => {
        // Enter in a text field submits a form. On any step but the last that
        // would post a half-filled registration, so it advances instead.
        if (event.key !== "Enter" || onReview) return;
        const target = event.target as HTMLElement;
        if (target.tagName === "TEXTAREA" || target.tagName === "BUTTON") return;
        event.preventDefault();
        goNext();
      }}
    >
      <div>
        <h1 className="text-[1.75rem] font-semibold tracking-tight text-ink">
          Open your Northbank account
        </h1>
        <p className="mt-2 text-[0.9375rem] leading-relaxed text-ink-muted">
          Takes about three minutes. Everything you enter is saved to your profile.
        </p>
      </div>

      <Stepper current={step} />

      {state.error ? <FormError>{state.error}</FormError> : null}

      <div>
        <h2 ref={headingRef} tabIndex={-1} className="text-base font-semibold text-ink outline-none">
          {currentStep.title}
        </h2>
        <p className="mt-1 text-[0.8125rem] leading-relaxed text-ink-muted">
          {currentStep.description}
        </p>
      </div>

      {step === "account" ? (
        <div className="space-y-5">
          <TextField
            label="Username"
            name="username"
            autoComplete="username"
            required
            requiredMark
            icon={<User aria-hidden="true" className="h-4 w-4" />}
            hint="3–50 characters"
            value={data.username}
            onChange={(e) => set("username", e.target.value)}
            error={errors.username}
            disabled={pending}
          />

          <TextField
            label="Email address"
            name="email"
            type="email"
            inputMode="email"
            autoComplete="email"
            required
            requiredMark
            icon={<AtSign aria-hidden="true" className="h-4 w-4" />}
            value={data.email}
            onChange={(e) => set("email", e.target.value)}
            error={errors.email}
            disabled={pending}
          />

          <div>
            <PasswordField
              label="Password"
              name="password"
              autoComplete="new-password"
              required
              requiredMark
              value={data.password}
              onChange={(e) => set("password", e.target.value)}
              error={errors.password}
              disabled={pending}
            />
            <PasswordRequirements value={data.password} />
          </div>

          <PasswordField
            label="Confirm password"
            name="confirmPassword"
            autoComplete="new-password"
            required
            requiredMark
            value={data.confirmPassword}
            onChange={(e) => set("confirmPassword", e.target.value)}
            error={errors.confirmPassword}
            disabled={pending}
          />
        </div>
      ) : null}

      {step === "personal" ? (
        <div className="space-y-5">
          <div className="grid gap-5 sm:grid-cols-2">
            <TextField
              label="First name"
              name="firstName"
              autoComplete="given-name"
              required
              requiredMark
              value={data.firstName}
              onChange={(e) => set("firstName", e.target.value)}
              error={errors.firstName}
              disabled={pending}
            />
            <TextField
              label="Last name"
              name="lastName"
              autoComplete="family-name"
              required
              requiredMark
              value={data.lastName}
              onChange={(e) => set("lastName", e.target.value)}
              error={errors.lastName}
              disabled={pending}
            />
          </div>

          <TextField
            label="Middle name"
            name="middleName"
            autoComplete="additional-name"
            hint="Optional"
            value={data.middleName}
            onChange={(e) => set("middleName", e.target.value)}
            error={errors.middleName}
            disabled={pending}
          />

          <TextField
            label="Date of birth"
            name="dateOfBirth"
            type="date"
            autoComplete="bday"
            required
            requiredMark
            hint="You must be 18 or older to open an account"
            max={new Date().toISOString().slice(0, 10)}
            value={data.dateOfBirth}
            onChange={(e) => set("dateOfBirth", e.target.value)}
            error={errors.dateOfBirth}
            disabled={pending}
          />

          <TextField
            label="Phone number"
            name="phone"
            type="tel"
            inputMode="tel"
            autoComplete="tel"
            required
            requiredMark
            icon={<Phone aria-hidden="true" className="h-4 w-4" />}
            // Formatted as it is typed; normalised back to digits before the
            // request, so what is stored is not a display string.
            value={formatPhone(data.phone)}
            onChange={(e) => set("phone", normalizePhone(e.target.value))}
            error={errors.phone}
            disabled={pending}
          />
        </div>
      ) : null}

      {step === "address" ? (
        <div className="space-y-5">
          <TextField
            label="Street address"
            name="streetAddress"
            autoComplete="address-line1"
            required
            requiredMark
            value={data.streetAddress}
            onChange={(e) => set("streetAddress", e.target.value)}
            error={errors.streetAddress}
            disabled={pending}
          />

          <TextField
            label="Apartment, suite or unit"
            name="addressLine2"
            autoComplete="address-line2"
            hint="Optional"
            value={data.addressLine2}
            onChange={(e) => set("addressLine2", e.target.value)}
            error={errors.addressLine2}
            disabled={pending}
          />

          <TextField
            label="City"
            name="city"
            autoComplete="address-level2"
            required
            requiredMark
            value={data.city}
            onChange={(e) => set("city", e.target.value)}
            error={errors.city}
            disabled={pending}
          />

          <div className="grid gap-5 sm:grid-cols-2">
            <SelectField
              label="State"
              name="state"
              autoComplete="address-level1"
              required
              requiredMark
              value={data.state}
              onChange={(e) => set("state", e.target.value)}
              error={errors.state}
              disabled={pending}
            >
              <option value="">Select a state</option>
              {US_STATES.map((usState) => (
                <option key={usState.code} value={usState.code}>
                  {usState.name}
                </option>
              ))}
            </SelectField>

            <TextField
              label="ZIP code"
              name="postalCode"
              inputMode="numeric"
              autoComplete="postal-code"
              required
              requiredMark
              maxLength={10}
              value={data.postalCode}
              onChange={(e) => set("postalCode", e.target.value)}
              error={errors.postalCode}
              disabled={pending}
            />
          </div>
        </div>
      ) : null}

      {step === "identity" ? (
        <div className="space-y-5">
          <TextField
            label="Social Security number"
            name="ssn"
            inputMode="numeric"
            autoComplete="off"
            required
            requiredMark
            icon={<ShieldCheck aria-hidden="true" className="h-4 w-4" />}
            hint="We keep only the last four digits. The rest is not stored."
            placeholder="123-45-6789"
            maxLength={11}
            value={formatSsn(data.ssn)}
            onChange={(e) => set("ssn", normalizeSsn(e.target.value))}
            error={errors.ssn}
            disabled={pending}
          />

          <div>
            <label className="flex items-start gap-3">
              <input
                type="checkbox"
                name="acceptedTerms"
                checked={data.acceptedTerms}
                onChange={(e) => set("acceptedTerms", e.target.checked)}
                disabled={pending}
                aria-invalid={errors.acceptedTerms ? true : undefined}
                className="mt-0.5 h-4 w-4 shrink-0 rounded border-line-strong text-primary"
              />
              <span className="text-[0.8125rem] leading-relaxed text-ink-muted">
                I confirm the details I have given are mine and are accurate, and I accept the
                account terms.
              </span>
            </label>
            {errors.acceptedTerms ? <FieldMessage>{errors.acceptedTerms}</FieldMessage> : null}
          </div>
        </div>
      ) : null}

      {onReview ? (
        <>
          <ReviewStep data={data} onEdit={editFromReview} />

          {/*
           * The review screen is what gets submitted, so every answer travels
           * with it. These are ordinary form fields in a page the customer is
           * looking at — not storage. Nothing here outlives the submission.
           */}
          <input type="hidden" name="username" value={data.username} />
          <input type="hidden" name="email" value={data.email} />
          <input type="hidden" name="password" value={data.password} />
          <input type="hidden" name="confirmPassword" value={data.confirmPassword} />
          <input type="hidden" name="firstName" value={data.firstName} />
          <input type="hidden" name="middleName" value={data.middleName} />
          <input type="hidden" name="lastName" value={data.lastName} />
          <input type="hidden" name="dateOfBirth" value={data.dateOfBirth} />
          <input type="hidden" name="phone" value={data.phone} />
          <input type="hidden" name="streetAddress" value={data.streetAddress} />
          <input type="hidden" name="addressLine2" value={data.addressLine2} />
          <input type="hidden" name="city" value={data.city} />
          <input type="hidden" name="state" value={data.state} />
          <input type="hidden" name="postalCode" value={data.postalCode} />
          <input type="hidden" name="ssn" value={data.ssn} />
          {data.acceptedTerms ? <input type="hidden" name="acceptedTerms" value="on" /> : null}
        </>
      ) : null}

      <div className="flex items-center gap-3">
        {index > 0 ? (
          <Button type="button" variant="secondary" size="lg" onClick={goBack} disabled={pending}>
            <ArrowLeft aria-hidden="true" className="h-4 w-4" />
            Back
          </Button>
        ) : null}

        {onReview ? (
          <Button type="submit" size="lg" pending={pending} className="flex-1">
            {pending ? "Opening your account…" : "Open my account"}
          </Button>
        ) : (
          <Button type="button" size="lg" onClick={goNext} disabled={pending} className="flex-1">
            {returningToReview ? "Back to review" : "Continue"}
          </Button>
        )}
      </div>

      <p className="text-center text-sm text-ink-muted">
        Already have an account?{" "}
        <Link href="/login" className="font-medium text-primary underline-offset-4 hover:underline">
          Sign in
        </Link>
      </p>
    </form>
  );
}
