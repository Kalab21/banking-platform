"use client";

import { useEffect } from "react";
import { Button } from "@/components/ui/form";
import { ErrorState } from "@/components/ui/primitives";

/** Catches anything a page throws that wasn't handled as an expected API failure. */
export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("Unhandled error in banking console:", error);
  }, [error]);

  return (
    <div className="space-y-4">
      <ErrorState
        title="This page could not be displayed"
        message="Something unexpected happened. Trying again often clears it."
      />
      <Button onClick={reset} variant="secondary">
        Try again
      </Button>
    </div>
  );
}
