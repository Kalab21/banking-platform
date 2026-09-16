"use client";

import { forwardRef, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

/** Interactive form primitives. Every field is label-bound and reports its own error. */

type ButtonVariant = "primary" | "secondary" | "danger" | "ghost";

const VARIANTS: Record<ButtonVariant, string> = {
  primary: "bg-accent text-white hover:bg-accent-hover disabled:bg-accent/50",
  secondary:
    "border border-line-strong bg-surface text-ink hover:bg-sunken disabled:text-ink-subtle",
  danger: "bg-critical text-white hover:bg-critical/90 disabled:bg-critical/50",
  ghost: "text-accent hover:bg-accent-soft disabled:text-ink-subtle",
};

export function Button({
  children,
  variant = "primary",
  pending = false,
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  /** Disables the control and announces progress, preventing a double submit. */
  pending?: boolean;
}) {
  return (
    <button
      {...props}
      disabled={props.disabled || pending}
      aria-busy={pending || undefined}
      className={cn(
        "inline-flex items-center justify-center gap-2 rounded-md px-4 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed",
        VARIANTS[variant],
        className,
      )}
    >
      {pending ? (
        <span
          aria-hidden="true"
          className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent"
        />
      ) : null}
      {children}
    </button>
  );
}

interface FieldProps {
  label: string;
  name: string;
  error?: string;
  hint?: string;
  children?: ReactNode;
}

function FieldWrapper({
  label,
  name,
  error,
  hint,
  children,
}: FieldProps & { children: ReactNode }) {
  return (
    <div>
      <label htmlFor={name} className="block text-sm font-medium text-ink">
        {label}
      </label>
      {hint ? (
        <p id={`${name}-hint`} className="mt-0.5 text-xs text-ink-subtle">
          {hint}
        </p>
      ) : null}
      <div className="mt-1.5">{children}</div>
      {error ? (
        <p id={`${name}-error`} role="alert" className="mt-1.5 text-sm text-critical">
          {error}
        </p>
      ) : null}
    </div>
  );
}

const FIELD_CLASSES =
  "block w-full rounded-md border bg-surface px-3 py-2 text-sm text-ink placeholder:text-ink-subtle disabled:bg-sunken disabled:text-ink-subtle";

export const TextField = forwardRef<
  HTMLInputElement,
  InputHTMLAttributes<HTMLInputElement> & FieldProps
>(function TextField({ label, name, error, hint, className, ...props }, ref) {
  return (
    <FieldWrapper label={label} name={name} error={error} hint={hint}>
      <input
        {...props}
        ref={ref}
        id={name}
        name={name}
        aria-invalid={error ? true : undefined}
        aria-describedby={
          [hint ? `${name}-hint` : null, error ? `${name}-error` : null]
            .filter(Boolean)
            .join(" ") || undefined
        }
        className={cn(
          FIELD_CLASSES,
          error ? "border-critical" : "border-line-strong",
          className,
        )}
      />
    </FieldWrapper>
  );
});

export function SelectField({
  label,
  name,
  error,
  hint,
  children,
  className,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement> & FieldProps) {
  return (
    <FieldWrapper label={label} name={name} error={error} hint={hint}>
      <select
        {...props}
        id={name}
        name={name}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${name}-error` : undefined}
        className={cn(FIELD_CLASSES, error ? "border-critical" : "border-line-strong", className)}
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
  currency = "USD",
  ...props
}: InputHTMLAttributes<HTMLInputElement> & FieldProps & { currency?: string }) {
  return (
    <FieldWrapper label={label} name={name} error={error} hint={hint}>
      <div className="relative">
        <span
          aria-hidden="true"
          className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm text-ink-subtle"
        >
          $
        </span>
        <input
          {...props}
          id={name}
          name={name}
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0.00"
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${name}-error` : undefined}
          className={cn(
            FIELD_CLASSES,
            "tabular pl-7 pr-14",
            error ? "border-critical" : "border-line-strong",
          )}
        />
        <span
          aria-hidden="true"
          className="pointer-events-none absolute inset-y-0 right-3 flex items-center text-xs font-medium text-ink-subtle"
        >
          {currency}
        </span>
      </div>
    </FieldWrapper>
  );
}

/** Inline success confirmation shown after a completed action. */
export function SuccessNote({ children }: { children: ReactNode }) {
  return (
    <div
      role="status"
      className="rounded-md border border-positive/30 bg-positive-soft px-4 py-3 text-sm text-positive"
    >
      {children}
    </div>
  );
}

/** Inline failure message for a form submission. */
export function FormError({ children }: { children: ReactNode }) {
  return (
    <div
      role="alert"
      className="rounded-md border border-critical/30 bg-critical-soft px-4 py-3 text-sm text-critical"
    >
      {children}
    </div>
  );
}
