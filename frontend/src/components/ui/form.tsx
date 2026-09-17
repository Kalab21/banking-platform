"use client";

import { Check, Eye, EyeOff, X } from "lucide-react";
import {
  forwardRef,
  useId,
  useState,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
} from "react";
import { cn } from "@/lib/cn";

/**
 * Interactive form primitives.
 *
 * Every field is label-bound, reports its own error through `aria-describedby`,
 * and marks itself `aria-invalid`. State is never carried by colour alone: an
 * invalid field also gains an icon and a message, and a satisfied password rule
 * shows a tick rather than only turning green.
 */

// -------------------------------------------------------------------- buttons

type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
type ButtonSize = "md" | "lg";

const VARIANTS: Record<ButtonVariant, string> = {
  primary: "bg-primary text-white hover:bg-primary-hover disabled:bg-primary/45",
  secondary:
    "border border-line-strong bg-surface text-ink hover:bg-surface-hover disabled:text-ink-subtle",
  ghost: "text-primary hover:bg-primary-soft disabled:text-ink-subtle",
  danger: "bg-critical text-white hover:bg-critical/90 disabled:bg-critical/45",
};

const SIZES: Record<ButtonSize, string> = {
  // 44px and 48px: comfortably above the minimum touch target on a phone.
  md: "min-h-11 px-4 text-sm",
  lg: "min-h-12 px-5 text-[0.9375rem]",
};

/**
 * The button's own classes, for the cases where the control has to be a link.
 *
 * "Go to your dashboard" navigates, so it is an anchor — rendering it as a
 * button would take away middle-click, open-in-new-tab and the status bar. It
 * should still look like the primary action, so the styling is shared rather
 * than copied.
 */
export function buttonStyles(
  variant: ButtonVariant = "primary",
  size: ButtonSize = "md",
  className?: string,
) {
  return cn(
    "relative inline-flex items-center justify-center gap-2 rounded-[var(--radius-control)]",
    "font-medium transition-colors duration-150 disabled:cursor-not-allowed",
    VARIANTS[variant],
    SIZES[size],
    className,
  );
}

export function Button({
  children,
  variant = "primary",
  size = "md",
  pending = false,
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Disables the control and announces progress, preventing a double submit. */
  pending?: boolean;
}) {
  return (
    <button
      {...props}
      disabled={props.disabled || pending}
      aria-busy={pending || undefined}
      className={cn(
        "relative inline-flex items-center justify-center gap-2 rounded-[var(--radius-control)]",
        "font-medium transition-colors duration-150 disabled:cursor-not-allowed",
        VARIANTS[variant],
        SIZES[size],
        className,
      )}
    >
      {/*
       * The spinner sits in a fixed-width slot that is always present, so the
       * label does not shift sideways the moment the button starts working.
       */}
      <span aria-hidden="true" className={cn("w-4 shrink-0", !pending && "hidden")}>
        <span className="block h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
      </span>
      {children}
    </button>
  );
}

/** An icon-only control. The label is required — it is the accessible name. */
export function IconButton({
  label,
  children,
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { label: string; children: ReactNode }) {
  return (
    <button
      {...props}
      aria-label={label}
      className={cn(
        "inline-flex h-9 w-9 items-center justify-center rounded-lg text-ink-muted",
        "transition-colors duration-150 hover:bg-surface-subtle hover:text-ink",
        className,
      )}
    >
      {children}
    </button>
  );
}

// --------------------------------------------------------------------- fields

interface FieldProps {
  label: string;
  name: string;
  error?: string;
  hint?: string;
  /** Renders the asterisk and marks the control required. */
  requiredMark?: boolean;
}

/**
 * Field ids are generated, not taken from `name`.
 *
 * Several forms on one page legitimately share a field name — deposit, withdraw
 * and transfer all submit an `amount` — and reusing the name as the DOM id would
 * produce duplicate ids, which breaks label association for assistive technology
 * and for anything that resolves a label to its control.
 */
function useFieldIds(name: string) {
  const unique = useId();
  const id = `${name}-${unique}`;
  return { id, hintId: `${id}-hint`, errorId: `${id}-error` };
}

function describedBy(ids: { hintId: string; errorId: string }, hint?: string, error?: string) {
  return [hint ? ids.hintId : null, error ? ids.errorId : null].filter(Boolean).join(" ") || undefined;
}

function FieldWrapper({
  label,
  ids,
  error,
  hint,
  requiredMark,
  children,
}: Omit<FieldProps, "name"> & {
  ids: { id: string; hintId: string; errorId: string };
  children: ReactNode;
}) {
  return (
    <div>
      {/*
       * The asterisk sits beside the label, not inside it. Anything inside the
       * element becomes part of the control's accessible name, so a field would
       * be announced as "Password star" — and anything resolving a control by
       * its label would have to know to include it.
       */}
      <span className="flex items-baseline gap-0.5">
        <label htmlFor={ids.id} className="block text-sm font-medium text-ink">
          {label}
        </label>
        {requiredMark ? (
          <span aria-hidden="true" className="text-sm text-critical">
            *
          </span>
        ) : null}
      </span>
      {hint ? (
        <p id={ids.hintId} className="mt-0.5 text-[0.8125rem] text-ink-subtle">
          {hint}
        </p>
      ) : null}
      <div className="mt-1.5">{children}</div>
      {error ? <FieldMessage id={ids.errorId}>{error}</FieldMessage> : null}
    </div>
  );
}

/** The error line under a field. Announced, and carries an icon as well as colour. */
export function FieldMessage({ id, children }: { id?: string; children: ReactNode }) {
  return (
    <p id={id} role="alert" className="mt-1.5 flex items-start gap-1.5 text-[0.8125rem] text-critical">
      <X aria-hidden="true" className="mt-0.5 h-3.5 w-3.5 shrink-0" />
      <span>{children}</span>
    </p>
  );
}

const CONTROL_BASE =
  "block w-full rounded-[var(--radius-control)] border bg-surface text-sm text-ink " +
  "min-h-11 px-3.5 py-2.5 transition-colors duration-150 " +
  "placeholder:text-ink-subtle disabled:bg-surface-subtle disabled:text-ink-subtle";

function controlBorder(error?: string) {
  return error ? "border-critical" : "border-line-strong hover:border-ink-subtle";
}

export const TextField = forwardRef<
  HTMLInputElement,
  InputHTMLAttributes<HTMLInputElement> &
    FieldProps & {
      /** Decorative glyph inside the control; always hidden from assistive tech. */
      icon?: ReactNode;
    }
>(function TextField({ label, name, error, hint, requiredMark, icon, className, ...props }, ref) {
  const ids = useFieldIds(name);
  return (
    <FieldWrapper label={label} ids={ids} error={error} hint={hint} requiredMark={requiredMark}>
      <div className="relative">
        {icon ? (
          <span
            aria-hidden="true"
            className="pointer-events-none absolute inset-y-0 left-3.5 flex items-center text-ink-subtle"
          >
            {icon}
          </span>
        ) : null}
        <input
          {...props}
          ref={ref}
          id={ids.id}
          name={name}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(ids, hint, error)}
          className={cn(CONTROL_BASE, controlBorder(error), icon && "pl-10", className)}
        />
      </div>
    </FieldWrapper>
  );
});

/**
 * Password entry with a visibility toggle.
 *
 * The toggle is a real button with a changing accessible name, so a screen
 * reader user is told which state they are switching to. Toggling does not move
 * focus, and the field keeps `autoComplete` intact in both states.
 */
export function PasswordField({
  label,
  name,
  error,
  hint,
  requiredMark,
  className,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & FieldProps) {
  const ids = useFieldIds(name);
  const [visible, setVisible] = useState(false);

  return (
    <FieldWrapper label={label} ids={ids} error={error} hint={hint} requiredMark={requiredMark}>
      <div className="relative">
        <input
          {...props}
          id={ids.id}
          name={name}
          type={visible ? "text" : "password"}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(ids, hint, error)}
          className={cn(CONTROL_BASE, controlBorder(error), "pr-12", className)}
        />
        <button
          type="button"
          onClick={() => setVisible((v) => !v)}
          aria-label={visible ? "Hide password" : "Show password"}
          aria-pressed={visible}
          disabled={props.disabled}
          className={cn(
            "absolute inset-y-0 right-0 flex w-11 items-center justify-center rounded-r-[var(--radius-control)]",
            "text-ink-subtle transition-colors duration-150 hover:text-ink disabled:text-ink-subtle",
          )}
        >
          {visible ? (
            <EyeOff aria-hidden="true" className="h-4.5 w-4.5" />
          ) : (
            <Eye aria-hidden="true" className="h-4.5 w-4.5" />
          )}
        </button>
      </div>
    </FieldWrapper>
  );
}

export function SelectField({
  label,
  name,
  error,
  hint,
  requiredMark,
  children,
  className,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement> & FieldProps & { children?: ReactNode }) {
  const ids = useFieldIds(name);
  return (
    <FieldWrapper label={label} ids={ids} error={error} hint={hint} requiredMark={requiredMark}>
      <select
        {...props}
        id={ids.id}
        name={name}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(ids, hint, error)}
        className={cn(CONTROL_BASE, controlBorder(error), className)}
      >
        {children}
      </select>
    </FieldWrapper>
  );
}

/** Amount input with a currency adornment and numeric keyboard on mobile. */
export function MoneyField({
  label,
  name,
  error,
  hint,
  requiredMark,
  currency = "USD",
  ...props
}: InputHTMLAttributes<HTMLInputElement> & FieldProps & { currency?: string }) {
  const ids = useFieldIds(name);
  return (
    <FieldWrapper label={label} ids={ids} error={error} hint={hint} requiredMark={requiredMark}>
      <div className="relative">
        <span
          aria-hidden="true"
          className="pointer-events-none absolute inset-y-0 left-3.5 flex items-center text-sm text-ink-subtle"
        >
          $
        </span>
        <input
          {...props}
          id={ids.id}
          name={name}
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0.00"
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(ids, hint, error)}
          className={cn(CONTROL_BASE, controlBorder(error), "tabular pl-7 pr-14")}
        />
        <span
          aria-hidden="true"
          className="pointer-events-none absolute inset-y-0 right-3.5 flex items-center text-xs font-medium text-ink-subtle"
        >
          {currency}
        </span>
      </div>
    </FieldWrapper>
  );
}

// ------------------------------------------------------------------- messages

/** Inline success confirmation shown after a completed action. */
export function SuccessNote({ children }: { children: ReactNode }) {
  return (
    <div
      role="status"
      className="flex items-start gap-2.5 rounded-[var(--radius-control)] border border-positive/25 bg-positive-soft px-4 py-3 text-sm text-positive"
    >
      <Check aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
      <span>{children}</span>
    </div>
  );
}

/** Form-level failure. Distinct from a field error: this is the whole submission. */
export function FormError({ children }: { children: ReactNode }) {
  return (
    <div
      role="alert"
      className="flex items-start gap-2.5 rounded-[var(--radius-control)] border border-critical/25 bg-critical-soft px-4 py-3 text-sm text-critical"
    >
      <X aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
      <span>{children}</span>
    </div>
  );
}
