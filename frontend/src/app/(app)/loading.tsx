import { Card, CardBody, Skeleton } from "@/components/ui/primitives";

/**
 * Shown while a server component fetches.
 *
 * Mirrors the shape of the page that follows — header, balance surface, a row
 * of cards, then a wide and a narrow panel — so content lands in the space
 * already held for it instead of pushing the page around.
 *
 * Deliberately blocks, not numbers. A skeleton shaped like "$12,345.67" is a
 * figure the customer starts reading before it turns out to be nothing.
 * `prefers-reduced-motion` stills the pulse; the tokens handle that globally.
 */
export default function Loading() {
  return (
    <div className="space-y-6" aria-busy="true">
      <span className="sr-only">Loading</span>

      <div className="space-y-2">
        <Skeleton className="h-7 w-64" />
        <Skeleton className="h-4 w-80" />
      </div>

      {/* Balance surface. */}
      <div className="rounded-[var(--radius-card)] bg-navy/[0.06] px-6 py-8">
        <Skeleton className="h-3 w-28 bg-navy/10" />
        <Skeleton className="mt-3 h-11 w-64 bg-navy/10" />
        <Skeleton className="mt-3 h-4 w-40 bg-navy/10" />
      </div>

      {/* Account cards. */}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {Array.from({ length: 3 }).map((_, i) => (
          <div key={i} className="rounded-[var(--radius-card)] border border-line bg-surface p-5">
            <div className="flex items-center gap-3">
              <Skeleton className="h-10 w-10 rounded-[10px]" />
              <div className="flex-1 space-y-2">
                <Skeleton className="h-4 w-32" />
                <Skeleton className="h-3 w-20" />
              </div>
            </div>
            <Skeleton className="mt-5 h-7 w-36" />
            <Skeleton className="mt-2 h-3 w-28" />
          </div>
        ))}
      </div>

      <div className="grid gap-6 lg:grid-cols-5">
        <div className="lg:col-span-3">
          <Card>
            <CardBody className="space-y-3">
              <Skeleton className="h-4 w-32" />
              <Skeleton className="h-52 w-full" />
            </CardBody>
          </Card>
        </div>
        <div className="lg:col-span-2">
          <Card>
            <CardBody className="space-y-4">
              {Array.from({ length: 5 }).map((_, i) => (
                <div key={i} className="flex items-center gap-3">
                  <Skeleton className="h-9 w-9 rounded-full" />
                  <div className="flex-1 space-y-2">
                    <Skeleton className="h-3.5 w-3/4" />
                    <Skeleton className="h-3 w-1/2" />
                  </div>
                  <Skeleton className="h-3.5 w-16" />
                </div>
              ))}
            </CardBody>
          </Card>
        </div>
      </div>
    </div>
  );
}
