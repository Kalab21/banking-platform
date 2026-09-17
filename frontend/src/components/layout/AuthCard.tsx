import type { ReactNode } from "react";

/**
 * The surface a signed-out form sits on.
 *
 * Width is a prop because the two forms are genuinely different shapes: sign-in
 * is a short column, and onboarding is a wizard with a progress indicator and
 * paired fields that need the room. Everything else about the surface is the
 * same, so it lives here rather than being repeated per page.
 */
const WIDTHS = {
  form: "max-w-[27rem]",
  wizard: "max-w-[34rem]",
} as const;

export function AuthCard({
  width = "form",
  children,
}: {
  width?: keyof typeof WIDTHS;
  children: ReactNode;
}) {
  return (
    <div
      className={`mx-auto w-full ${WIDTHS[width]} rounded-[var(--radius-card)] border border-line bg-surface p-6 shadow-[0_1px_2px_rgba(16,24,40,0.04),0_8px_24px_-12px_rgba(16,24,40,0.10)] sm:p-8`}
    >
      {children}
    </div>
  );
}
