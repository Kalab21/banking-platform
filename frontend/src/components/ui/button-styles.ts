import { cn } from "@/lib/cn";

/**
 * The button's visual contract, in a module with no client boundary.
 *
 * It lives here rather than beside the `Button` component because server
 * components need it: "Go to your dashboard" navigates, so it is an anchor
 * rather than a button — rendering it as a button would take away middle-click,
 * open-in-new-tab and the status bar — and the page that renders it is a server
 * component. A function exported from a `"use client"` module cannot be called
 * from one.
 */

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ButtonSize = "md" | "lg";

export const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  primary: "bg-primary text-white hover:bg-primary-hover disabled:bg-primary/45",
  secondary:
    "border border-line-strong bg-surface text-ink hover:bg-surface-hover disabled:text-ink-subtle",
  ghost: "text-primary hover:bg-primary-soft disabled:text-ink-subtle",
  danger: "bg-critical text-white hover:bg-critical/90 disabled:bg-critical/45",
};

export const BUTTON_SIZES: Record<ButtonSize, string> = {
  // 44px and 48px: comfortably above the minimum touch target on a phone.
  md: "min-h-11 px-4 text-sm",
  lg: "min-h-12 px-5 text-[0.9375rem]",
};

export function buttonStyles(
  variant: ButtonVariant = "primary",
  size: ButtonSize = "md",
  className?: string,
) {
  return cn(
    "relative inline-flex items-center justify-center gap-2 rounded-[var(--radius-control)]",
    "font-medium transition-colors duration-150 disabled:cursor-not-allowed",
    BUTTON_VARIANTS[variant],
    BUTTON_SIZES[size],
    className,
  );
}
