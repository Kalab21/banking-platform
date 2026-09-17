import { cn } from "@/lib/cn";

/**
 * Product mark.
 *
 * A geometric monogram rather than an illustration: it renders identically at
 * 24px in a sidebar and 40px on the sign-in panel, and costs nothing to load.
 * Deliberately not an imitation of any real bank's identity.
 */
export function Wordmark({
  compact = false,
  tone = "ink",
  size = "md",
}: {
  /** Mark only, no wordmark text. */
  compact?: boolean;
  /** `inverse` for the navy brand panel, `ink` for light surfaces. */
  tone?: "ink" | "inverse";
  size?: "sm" | "md" | "lg";
}) {
  const tile = {
    sm: "h-7 w-7 rounded-lg text-[0.8125rem]",
    md: "h-9 w-9 rounded-[10px] text-base",
    lg: "h-11 w-11 rounded-xl text-lg",
  }[size];

  const text = {
    sm: "text-sm",
    md: "text-base",
    lg: "text-xl",
  }[size];

  return (
    <span className="inline-flex items-center gap-2.5">
      <span
        aria-hidden="true"
        className={cn(
          "flex items-center justify-center font-semibold tracking-tight",
          tile,
          tone === "inverse" ? "bg-white text-navy" : "bg-navy text-white",
        )}
      >
        N
      </span>
      {compact ? null : (
        <span
          className={cn(
            "font-semibold tracking-tight",
            text,
            tone === "inverse" ? "text-white" : "text-ink",
          )}
        >
          Northbank
        </span>
      )}
    </span>
  );
}
