import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft, ArrowRight, CheckCircle2, Circle, CircleDot } from "lucide-react";
import { requireSession } from "@/lib/session";
import { getApplication, getOffers } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardBody,
  CardHeader,
  Detail,
  DetailList,
  ErrorState,
  PageHeader,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDateTime, humanise } from "@/lib/format";
import { OfferActions } from "@/features/credit/OfferActions";
import { OfferTerms, productHref } from "@/features/credit/application-view";
import { productLabel } from "@/features/credit/products";
import {
  awaitingCustomer,
  offerHeading,
  offerIsOpen,
  offerLapsed,
  statusSummary,
} from "@/features/credit/reasons";
import { applicationTimeline, offerSummary, type TimelineStep } from "@/features/credit/timeline";
import type { Offer } from "@/types/api";

export const metadata: Metadata = { title: "Application" };

const OFFER_STATES = ["OFFERED", "ACCEPTED", "DECLINED", "PROVISIONING", "PROVISIONED"];

export default async function ApplicationDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const session = await requireSession();
  const { id } = await params;
  const applicationId = Number(id);
  if (!Number.isInteger(applicationId) || applicationId <= 0) notFound();

  let application;
  try {
    application = await getApplication(applicationId);
  } catch (error) {
    // Someone else's application is answered exactly like one that does not
    // exist: the page does not confirm that the id is in use.
    if (error instanceof ApiError && (error.isNotFound || error.status === 403)) notFound();
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Application" />
          <ErrorState title="We could not load this application" message={error.userMessage} />
        </>
      );
    }
    throw error;
  }
  // Staff reach applications through the review workbench, not this page.
  if (application.userId !== session.userId) notFound();

  let offer: Offer | undefined;
  let offerFailed = false;
  if (OFFER_STATES.includes(application.status)) {
    try {
      offer = (await getOffers(application.id))[0];
    } catch {
      offerFailed = true;
    }
  }

  const product = productLabel(application.applicationType);
  const href = productHref(application);
  const lapsed = offerLapsed(application, offer);
  const steps = applicationTimeline(application, offer);

  return (
    <>
      <Link
        href="/applications"
        className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
      >
        <ArrowLeft aria-hidden="true" className="h-4 w-4" />
        Back to applications
      </Link>

      <section
        aria-labelledby="application-heading"
        className="rounded-[var(--radius-card)] border border-line bg-surface p-6 sm:p-7"
        data-application-id={application.id}
      >
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 id="application-heading" className="text-xl font-semibold tracking-tight text-ink">
              {product} application
            </h1>
            <p className="mt-1 text-sm text-ink-subtle">Application #{application.id}</p>
          </div>
          {lapsed ? (
            <Badge tone="neutral">Offer expired</Badge>
          ) : (
            <Badge tone={statusTone(application.status)}>{humanise(application.status)}</Badge>
          )}
        </div>
        <p className="mt-4 text-sm text-ink-muted">{statusSummary(application, offer)}</p>

        {href ? (
          <Link
            href={href}
            className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
          >
            View your {product.toLowerCase()}
            <ArrowRight aria-hidden className="size-4" />
          </Link>
        ) : null}
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader title="What has happened" />
          <CardBody>
            <Timeline steps={steps} />
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="What you told us" />
          <CardBody>
            <DetailList>
              {application.requestedAmount !== null ? (
                <Detail label="You asked for">
                  {formatCurrency(application.requestedAmount, application.currency)}
                </Detail>
              ) : null}
              {application.termMonths !== null ? (
                <Detail label="Over">{application.termMonths} months</Detail>
              ) : null}
              {application.purpose ? <Detail label="Purpose">{application.purpose}</Detail> : null}
              {application.annualIncome !== null ? (
                <Detail label="Annual income">
                  {formatCurrency(application.annualIncome, application.currency)}
                </Detail>
              ) : null}
              {application.monthlyDebtObligations !== null ? (
                <Detail label="Monthly debt payments">
                  {formatCurrency(application.monthlyDebtObligations, application.currency)}
                </Detail>
              ) : null}
            </DetailList>
          </CardBody>
        </Card>
      </div>

      {offer ? (
        <Card>
          <CardHeader title={offerHeading(offer)} />
          <CardBody className="grid gap-4">
            <OfferTerms offer={offer} />
            {awaitingCustomer(application) && offerIsOpen(offer) ? (
              <OfferActions applicationId={application.id} terms={offerSummary(offer)} />
            ) : null}
          </CardBody>
        </Card>
      ) : null}

      {offerFailed || (awaitingCustomer(application) && !offer) ? (
        <ErrorState
          title="We could not load this offer"
          message="The terms are not available right now, so you cannot accept or decline it here. Refresh and try again."
        />
      ) : null}

      {lapsed || application.status === "REJECTED" || application.status === "DECLINED" ? (
        <Link
          href="/credit"
          className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
        >
          Explore credit
          <ArrowRight aria-hidden className="size-4" />
        </Link>
      ) : null}
    </>
  );
}

function Timeline({ steps }: { steps: TimelineStep[] }) {
  return (
    <ol className="space-y-4" aria-label="Application history">
      {steps.map((step) => {
        const Icon = step.state === "done" ? CheckCircle2 : step.state === "current" ? CircleDot : Circle;
        const tone =
          step.state === "done" ? "text-positive" : step.state === "current" ? "text-primary" : "text-ink-subtle";
        return (
          <li
            key={step.id}
            className="flex gap-3"
            data-step={step.id}
            aria-current={step.state === "current" ? "step" : undefined}
          >
            <Icon aria-hidden="true" className={`mt-0.5 h-5 w-5 shrink-0 ${tone}`} />
            <div className="min-w-0">
              <p className={`text-sm font-medium ${step.state === "upcoming" ? "text-ink-subtle" : "text-ink"}`}>
                {step.label}
                {step.state === "upcoming" ? <span className="sr-only"> (not yet)</span> : null}
              </p>
              {step.at ? <p className="text-xs text-ink-subtle">{formatDateTime(step.at)}</p> : null}
              {step.detail ? <p className="mt-0.5 text-xs text-ink-muted">{step.detail}</p> : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
