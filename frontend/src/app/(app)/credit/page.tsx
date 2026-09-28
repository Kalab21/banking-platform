import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { Card, CardBody, CardHeader, PageHeader } from "@/components/ui/primitives";
import { CREDIT_PRODUCTS, type CreditProduct } from "@/features/credit/products";

export const metadata: Metadata = { title: "Explore credit" };

/**
 * The four products a customer may apply for.
 *
 * Nothing on this page claims an outcome. There is no "pre-qualified", no
 * "instant decision", no indicative rate and no example APR — Northbank decides
 * each application on its own figures, and a number shown here before that
 * happens would be a promise the bank has not made. What the customer gets is
 * what the product is, what applying will ask of them, and what happens next.
 */

/** What the application form will ask, read from the same definition the form uses. */
function asks(product: CreditProduct): string[] {
  const list: string[] = [];
  if (product.asks.amount) list.push("How much you want to borrow");
  if (product.asks.term && product.terms) {
    const [first, last] = [product.terms[0], product.terms[product.terms.length - 1]];
    list.push(`How long to repay over, from ${first} to ${last} months`);
  }
  if (product.asks.asset) {
    list.push(product.type === "MORTGAGE" ? "The value of the property" : "The value of the vehicle");
  }
  if (product.asks.downPayment) list.push("Any deposit you are putting down");
  if (product.asks.purpose) {
    list.push(product.type === "PERSONAL_LOAN" ? "What the loan is for" : "What it is for, if you want to say");
  }
  list.push("Your annual income, and what you already pay towards other debts");
  return list;
}

const slug = (product: CreditProduct) => product.type.toLowerCase().replace(/_/g, "-");

const NEXT_STEPS = [
  "You tell us what you need and about your finances. It takes three short steps.",
  "A demo policy decides straight away, or passes the application to a person — for example while your identity check is unfinished.",
  "If we can lend, you see the amount, the rate and the term before anything is set up. The offer is yours to accept or decline.",
  "Once you accept, we set the product up and it appears under Credit cards or Loans.",
];

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
            <CardBody className="flex h-full flex-col gap-3">
              <div>
                <h2 className="text-base font-semibold text-ink">{product.name}</h2>
                <p className="mt-1 text-sm text-ink-muted">{product.summary}</p>
              </div>

              <div>
                <p className="text-xs font-semibold uppercase tracking-wide text-ink-subtle">
                  We will ask for
                </p>
                <ul className="mt-1.5 list-disc space-y-1 pl-5 text-sm text-ink-muted">
                  {asks(product).map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </div>

              <div className="mt-auto pt-2">
                <Link
                  href={`/credit/${slug(product)}`}
                  className="inline-flex min-h-11 items-center gap-1 text-sm font-medium text-primary hover:underline"
                >
                  Apply
                  <span className="sr-only"> for a {product.name.toLowerCase()}</span>
                  <ArrowRight aria-hidden className="size-4" />
                </Link>
              </div>
            </CardBody>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader title="What happens when you apply" />
        <CardBody>
          <ol className="list-decimal space-y-2 pl-5 text-sm text-ink-muted">
            {NEXT_STEPS.map((step) => (
              <li key={step}>{step}</li>
            ))}
          </ol>
          <p className="mt-3 text-sm text-ink-muted">
            You can follow each application, and see its history, under{" "}
            <Link href="/applications" className="font-medium text-primary hover:underline">
              My applications
            </Link>
            .
          </p>
        </CardBody>
      </Card>

      <p className="text-xs text-ink-muted">
        Northbank is a portfolio demonstration. Decisions are made by a demo policy against
        synthetic data — there is no credit bureau behind them, and no real money is lent.
      </p>
    </>
  );
}
