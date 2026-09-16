/** Product mark. A demo identity, deliberately not imitating any real bank. */
export function Wordmark({ compact = false }: { compact?: boolean }) {
  return (
    <span className="inline-flex items-center gap-2">
      <span
        aria-hidden="true"
        className="flex h-8 w-8 items-center justify-center rounded bg-accent text-sm font-bold text-white"
      >
        N
      </span>
      {compact ? null : (
        <span className="text-base font-semibold tracking-tight text-ink">Northbank</span>
      )}
    </span>
  );
}
