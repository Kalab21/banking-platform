import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";
import { formatCurrency } from "@/lib/format";

/** Shared presentational building blocks. Server-safe — no client hooks here. */

// ------------------------------------------------------------------- surfaces

/**
 * The surface most content sits on.
 *
 * `min-w-0` is load-bearing rather than cosmetic. A grid or flex item defaults
 * to `min-width: auto`, which means it refuses to shrink below the intrinsic
 * width of its widest child — so a card holding a select with long option text,
 * or a table with a minimum width, pushes the whole page wider than the screen
 * and produces a horizontal scrollbar on a phone. This lets the card shrink and
 * leaves the scrolling to whichever child actually needs it.
 */
export function Card({
  children,
  className,
  as: Tag = "section",
}: {
  children: ReactNode;
  className?: string;
  as?: "section" | "div" | "article";
}) {
  return (
    <Tag className={cn("min-w-0 rounded-lg border border-line bg-surface", className)}>
      {children}
    </Tag>
  );
}

export function CardHeader({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
      <div className="min-w-0">
        <h2 className="text-sm font-semibold text-ink">{title}</h2>
        {description ? <p className="mt-0.5 text-sm text-ink-subtle">{description}</p> : null}
      </div>
      {action ? <div className="shrink-0">{action}</div> : null}
    </div>
  );
}

export function CardBody({
  children,
  className,
  ...props
}: { children: ReactNode; className?: string } & HTMLAttributes<HTMLDivElement>) {
  return (
    <div {...props} className={cn("px-5 py-4", className)}>
      {children}
    </div>
  );
}

// --------------------------------------------------------------------- badges

type Tone = "neutral" | "positive" | "caution" | "critical" | "accent";

const TONE_CLASSES: Record<Tone, string> = {
  neutral: "bg-sunken text-ink-muted",
  positive: "bg-positive-soft text-positive",
  caution: "bg-caution-soft text-caution",
  critical: "bg-critical-soft text-critical",
  accent: "bg-accent-soft text-accent",
};

export function Badge({ children, tone = "neutral" }: { children: ReactNode; tone?: Tone }) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded px-2 py-0.5 text-xs font-medium",
        TONE_CLASSES[tone],
      )}
    >
      {children}
    </span>
  );
}

/** Maps a backend status string to a tone, so colour carries meaning consistently. */
export function statusTone(status: string | null | undefined): Tone {
  switch (status) {
    case "ACTIVE":
    case "APPROVED":
    case "VERIFIED":
    case "PAID":
    case "PAID_OFF":
    case "COMPLETED":
    case "PROCESSED":
    // An application whose product has been confirmed to exist.
    case "PROVISIONED":
      return "positive";
    case "PENDING":
    case "IN_REVIEW":
    // An application still moving through its lifecycle. PROVISIONING is
    // deliberately not positive: a product has been asked for, not confirmed.
    case "SUBMITTED":
    case "MANUAL_REVIEW":
    case "OFFERED":
    case "ACCEPTED":
    case "PROVISIONING":
    case "PARTIAL":
    case "SCHEDULED":
      return "caution";
    case "FROZEN":
    case "CLOSED":
    case "REJECTED":
    case "MISSED":
    case "DEFAULTED":
    case "FAILED":
    case "BLOCKED":
    case "OVERDRAWN":
      return "critical";
    default:
      return "neutral";
  }
}

// ------------------------------------------------------------------- feedback

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-12 text-center">
      <p className="text-sm font-medium text-ink">{title}</p>
      {description ? (
        <p className="mt-1 max-w-sm text-sm text-ink-subtle">{description}</p>
      ) : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  );
}

export function ErrorState({ title, message }: { title?: string; message: string }) {
  return (
    <div role="alert" className="rounded-lg border border-critical/30 bg-critical-soft px-5 py-4">
      <p className="text-sm font-semibold text-critical">{title ?? "Something went wrong"}</p>
      <p className="mt-1 text-sm text-critical/90">{message}</p>
    </div>
  );
}

/** Skeleton block for loading states driven by Suspense. */
export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("animate-pulse rounded bg-sunken", className)} aria-hidden="true" />;
}

export function LoadingCard({ rows = 3 }: { rows?: number }) {
  return (
    <Card>
      <CardBody className="space-y-3">
        <span className="sr-only">Loading</span>
        {Array.from({ length: rows }).map((_, i) => (
          <Skeleton key={i} className="h-4 w-full" />
        ))}
      </CardBody>
    </Card>
  );
}

// ---------------------------------------------------------------------- stats

export function StatTile({
  label,
  value,
  hint,
  tone = "neutral",
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: Tone;
}) {
  return (
    <div className="rounded-lg border border-line bg-surface px-5 py-4">
      <p className="text-xs font-medium uppercase tracking-wide text-ink-subtle">{label}</p>
      <p
        className={cn(
          "tabular mt-2 text-2xl font-semibold",
          tone === "critical" ? "text-critical" : "text-ink",
        )}
      >
        {value}
      </p>
      {hint ? <p className="mt-1 text-xs text-ink-subtle">{hint}</p> : null}
    </div>
  );
}

// ------------------------------------------------------------------ financial

/**
 * A monetary figure.
 *
 * Tabular numerals so that a column of amounts lines up digit-for-digit, which
 * is the difference between a list of numbers and a statement. Size is a prop
 * because the same component sets a headline balance and a row amount.
 */
export function Money({
  amount,
  currency = "USD",
  size = "md",
  signed = false,
  className,
}: {
  amount: number | null | undefined;
  currency?: string;
  size?: "sm" | "md" | "lg" | "hero";
  /** Prefixes an explicit + or − — for a row where direction is the point. */
  signed?: boolean;
  className?: string;
}) {
  const sizes = {
    sm: "text-sm",
    md: "text-base",
    lg: "text-2xl font-semibold tracking-tight",
    hero: "text-[2.5rem] leading-[1.1] font-semibold tracking-tight sm:text-[3rem]",
  } as const;

  const known = amount !== null && amount !== undefined && Number.isFinite(amount);
  const sign = signed && known ? (amount >= 0 ? "+" : "−") : "";
  const value = formatCurrency(known && signed ? Math.abs(amount) : amount, currency);

  return (
    <span className={cn("tabular", sizes[size], className)}>
      {sign}
      {value}
    </span>
  );
}

/**
 * A bounded progress bar with a real accessible name.
 *
 * Clamped on purpose: a balance above its limit, or a loan whose figures have
 * not settled, would otherwise draw a bar past the end of its track. The number
 * shown alongside is the true one; only the drawing is clamped.
 */
export function ProgressBar({
  value,
  label,
  tone = "primary",
}: {
  /** Percentage, 0–100. Values outside that range are clamped for display. */
  value: number;
  /** Read by assistive technology. Say what the bar measures, not "progress". */
  label: string;
  tone?: "primary" | "caution" | "critical";
}) {
  const safe = Number.isFinite(value) ? Math.min(Math.max(value, 0), 100) : 0;
  const fill = {
    primary: "bg-primary",
    caution: "bg-caution",
    critical: "bg-critical",
  }[tone];

  return (
    <div
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(safe)}
      aria-label={label}
      className="h-2 w-full overflow-hidden rounded-full bg-sunken"
    >
      <div className={cn("h-full rounded-full transition-[width]", fill)} style={{ width: `${safe}%` }} />
    </div>
  );
}

/**
 * Label-and-value pairs.
 *
 * A definition list rather than a grid of divs, because that is what this is,
 * and a screen reader announces the pairing instead of two unrelated strings.
 */
export function DetailList({
  children,
  columns = 2,
  className,
}: {
  children: ReactNode;
  columns?: 1 | 2 | 3;
  className?: string;
}) {
  const cols = {
    1: "grid-cols-1",
    2: "grid-cols-1 sm:grid-cols-2",
    3: "grid-cols-1 sm:grid-cols-2 lg:grid-cols-3",
  }[columns];

  return <dl className={cn("grid gap-x-6 gap-y-4", cols, className)}>{children}</dl>;
}

export function Detail({
  label,
  children,
  className,
}: {
  label: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("min-w-0", className)}>
      <dt className="text-xs font-medium uppercase tracking-wide text-ink-subtle">{label}</dt>
      <dd className="mt-1 text-sm text-ink">{children}</dd>
    </div>
  );
}

// ---------------------------------------------------------------------- table

export function TableShell({ children, label }: { children: ReactNode; label: string }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[40rem] border-collapse text-sm">
        <caption className="sr-only">{label}</caption>
        {children}
      </table>
    </div>
  );
}

export function Th({
  children,
  align = "left",
}: {
  children: ReactNode;
  align?: "left" | "right";
}) {
  return (
    <th
      scope="col"
      className={cn(
        "border-b border-line bg-sunken px-4 py-2.5 text-xs font-semibold uppercase tracking-wide text-ink-subtle",
        align === "right" ? "text-right" : "text-left",
      )}
    >
      {children}
    </th>
  );
}

export function Td({
  children,
  align = "left",
  className,
}: {
  children: ReactNode;
  align?: "left" | "right";
  className?: string;
}) {
  return (
    <td
      className={cn(
        "border-b border-line px-4 py-3 text-ink",
        align === "right" ? "tabular text-right" : "text-left",
        className,
      )}
    >
      {children}
    </td>
  );
}

// -------------------------------------------------------------------- headings

export function PageHeader({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-xl font-semibold tracking-tight text-ink">{title}</h1>
        {description ? <p className="mt-1 text-sm text-ink-muted">{description}</p> : null}
      </div>
      {action}
    </header>
  );
}
