import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Card, PageHeader } from "@/components/ui/primitives";
import { CREDIT_PRODUCTS } from "@/features/credit/products";

export const metadata: Metadata = { title: "Explore credit" };

/**
 * The four products a customer may apply for.
 *
 * Nothing on this page claims an outcome. There is no "pre-qualified", no
 * "instant decision", no indicative rate and no example APR — Northbank decides
 * each application on its own figures, and a number shown here before that
 * happens would be a promise the bank has not made. What the customer gets is
 * what the product is and what applying will ask of them.
 */
export default function ExploreCreditPage() {
  return (
    <>
      <PageHeader
        title="Explore credit"
        description="Apply for a card or a loan. We will tell you what we can offer, and the terms are yours to accept or decline."
      />

      <div className="grid gap-4 sm:grid-cols-2">
        {CREDIT_PRODUCTS.map((product) => (
          <Card key={product.type}>
            <div className="flex h-full flex-col gap-3">
              <div>
                <h2 className="text-base font-semibold text-[var(--text-strong)]">
                  {product.name}
                </h2>
                <p className="mt-1 text-sm text-[var(--text-muted)]">{product.summary}</p>
              </div>

              <div className="mt-auto pt-2">
                <Link
                  href={`/credit/${product.type.toLowerCase().replace(/_/g, "-")}`}
                  className="inline-flex items-center gap-1 text-sm font-medium text-[var(--accent)] hover:underline"
                >
                  Apply
                  <ArrowRight aria-hidden className="size-4" />
                </Link>
              </div>
            </div>
          </Card>
        ))}
      </div>

      <p className="mt-6 text-xs text-[var(--text-muted)]">
        Northbank is a portfolio demonstration. Decisions are made by a demo policy against
        synthetic data — there is no credit bureau behind them, and no real money is lent.
      </p>
    </>
  );
}
