import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { requireSession } from "@/lib/session";
import { getApplications, getOffers } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardBody,
  Detail,
  DetailList,
  EmptyState,
  ErrorState,
  PageHeader,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDate, formatPercent, humanise } from "@/lib/format";
import { OfferActions } from "@/features/credit/OfferActions";
import { productLabel } from "@/features/credit/products";
import {
  awaitingCustomer,
  offerHeading,
  offerIsOpen,
  offerLapsed,
  statusSummary,
} from "@/features/credit/reasons";
import type { Application, Offer } from "@/types/api";

export const metadata: Metadata = { title: "My applications" };

/** The product an application produced, once one really exists. */
function productHref(application: Application): string | null {
  // PROVISIONED and a real id, or nothing. PROVISIONING means the product has
  // been asked for and not yet confirmed, and linking to it then would promise
  // a card or a loan that may not exist — which is the failure the whole
  // lifecycle was rebuilt to stop.
  if (application.status !== "PROVISIONED" || !application.productId) return null;

  switch (application.applicationType) {
    case "CREDIT_CARD":
      return `/cards/${application.productId}`;
    case "PERSONAL_LOAN":
    case "AUTO_LOAN":
    case "MORTGAGE":
      return `/loans/${application.productId}`;
    case "CHECKING_ACCOUNT":
    case "SAVINGS_ACCOUNT":
      return `/accounts/${application.productId}`;
    default:
      return null;
  }
}

function OfferTerms({ offer }: { offer: Offer }) {
  return (
    <DetailList>
      {offer.approvedAmount !== null ? (
        <Detail label="Amount">{formatCurrency(offer.approvedAmount, offer.currency)}</Detail>
      ) : null}
      {offer.creditLimit !== null ? (
        <Detail label="Credit limit">{formatCurrency(offer.creditLimit, offer.currency)}</Detail>
      ) : null}
      {offer.cardTier ? <Detail label="Card">{humanise(offer.cardTier)}</Detail> : null}
      {offer.apr !== null ? <Detail label="APR">{formatPercent(offer.apr)}</Detail> : null}
      {offer.termMonths !== null ? (
        <Detail label="Term">{offer.termMonths} months</Detail>
      ) : null}
      {offer.monthlyPayment !== null ? (
        <Detail label="Estimated monthly payment">
          {formatCurrency(offer.monthlyPayment, offer.currency)}
        </Detail>
      ) : null}
      {offer.status === "OFFERED" && offer.expiresAt ? (
        <Detail label="Offer valid until">{formatDate(offer.expiresAt)}</Detail>
      ) : null}
    </DetailList>
  );
}

export default async function ApplicationsPage() {
  const session = await requireSession();

  let applications: Application[];
  try {
    // Newest first: the application a customer has just submitted is the one
    // they came here to see.
    applications = [...(await getApplications(session.userId))].sort((a, b) => b.id - a.id);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="My applications" />
          <ErrorState title="We could not load your applications" message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  // Offers only matter where the lifecycle has produced one, so only those
  // applications are asked about.
  const withOffers = applications.filter((application) =>
    ["OFFERED", "ACCEPTED", "DECLINED", "PROVISIONING", "PROVISIONED"].includes(
      application.status,
    ),
  );
  const offersByApplication = new Map<number, Offer>();
  const offerErrors = new Set<number>();
  const fetched = await Promise.all(
    withOffers.map(async (application) => {
      try {
        return [application.id, (await getOffers(application.id))[0]] as const;
      } catch {
        // An offer that cannot be read should not take the whole page down.
        offerErrors.add(application.id);
        return [application.id, undefined] as const;
      }
    }),
  );
  for (const [id, offer] of fetched) {
    if (offer) offersByApplication.set(id, offer);
  }

  return (
    <>
      <PageHeader
        title="My applications"
        description="Everything you have applied for, and where each one has got to."
      />

      {applications.length === 0 ? (
        <EmptyState
          title="No applications yet"
          description="When you apply for a card or a loan, it will appear here with its decision and any offer."
          action={
            <Link
              href="/credit"
              className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
            >
              Explore credit
              <ArrowRight aria-hidden className="size-4" />
            </Link>
          }
        />
      ) : (
        <div className="grid gap-4">
          {applications.map((application) => {
            const offer = offersByApplication.get(application.id);
            const href = productHref(application);
            const lapsed = offerLapsed(application, offer);

            return (
              <Card key={application.id}>
                <CardBody className="grid gap-3" data-application-id={application.id}>
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div>
                      <h2 className="text-base font-semibold text-ink">
                        {productLabel(application.applicationType)}
                      </h2>
                      <p className="mt-0.5 text-xs text-ink-muted">
                        Applied {formatDate(application.appliedAt ?? application.createdAt)}
                      </p>
                    </div>
                    {lapsed ? (
                      <Badge tone="neutral">Offer expired</Badge>
                    ) : (
                      <Badge tone={statusTone(application.status)}>
                        {humanise(application.status)}
                      </Badge>
                    )}
                  </div>

                  <p className="text-sm text-ink-muted">{statusSummary(application, offer)}</p>

                  {application.requestedAmount !== null || application.termMonths !== null ? (
                    <DetailList>
                      {application.requestedAmount !== null ? (
                        <Detail label="You asked for">
                          {formatCurrency(application.requestedAmount, application.currency)}
                        </Detail>
                      ) : null}
                      {application.termMonths !== null ? (
                        <Detail label="Over">{application.termMonths} months</Detail>
                      ) : null}
                    </DetailList>
                  ) : null}

                  {offer ? (
                    <section
                      aria-labelledby={`offer-${application.id}`}
                      className="rounded-[var(--radius-control)] border border-line p-3"
                    >
                      <h3 id={`offer-${application.id}`} className="mb-2 text-sm font-semibold text-ink">
                        {offerHeading(offer)}
                      </h3>
                      <OfferTerms offer={offer} />
                    </section>
                  ) : null}

                  {offerErrors.has(application.id) || (awaitingCustomer(application) && !offer) ? (
                    <ErrorState
                      title="We could not load this offer"
                      message="The terms are not available right now, so you cannot accept or decline it here. Refresh and try again."
                    />
                  ) : null}

                  {awaitingCustomer(application) && offer && offerIsOpen(offer) ? (
                    <OfferActions applicationId={application.id} />
                  ) : null}

                  {lapsed ? (
                    <Link
                      href="/credit"
                      className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
                    >
                      Apply again
                      <ArrowRight aria-hidden className="size-4" />
                    </Link>
                  ) : null}

                  {href ? (
                    <Link
                      href={href}
                      className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
                    >
                      View your {productLabel(application.applicationType).toLowerCase()}
                      <ArrowRight aria-hidden className="size-4" />
                    </Link>
                  ) : null}
                </CardBody>
              </Card>
            );
          })}
        </div>
      )}
    </>
  );
}
