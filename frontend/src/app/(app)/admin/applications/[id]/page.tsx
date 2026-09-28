import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { requireStaffSession } from "@/lib/session";
import { getApplication, getApplicationDecisions, getOffers, getUser } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardBody,
  CardHeader,
  Detail,
  DetailList,
  EmptyState,
  ErrorState,
  PageHeader,
  TableShell,
  Td,
  Th,
  statusTone,
} from "@/components/ui/primitives";
import { formatCurrency, formatDateTime, formatPercent, humanise } from "@/lib/format";
import { productLabel } from "@/features/credit/products";
import { ReviewDecisionForm } from "@/features/admin/ReviewDecisionForm";
import type { DecisionSnapshot } from "@/types/api";

export const metadata: Metadata = { title: "Review application" };

const money = (value: number | null, currency: string) => (value === null ? "—" : formatCurrency(value, currency));
const ratio = (value: number | null) => (value === null ? "—" : formatPercent(value * 100));

/**
 * One application as a reviewer needs to see it: what the customer stated,
 * every decision recorded on it in order, and -- if it is waiting on a person
 * -- the decision to make. Policy and reviewer decisions are both kept and both
 * shown; a reviewer's approval does not overwrite the policy's referral.
 */
export default async function AdminApplicationPage({ params }: { params: Promise<{ id: string }> }) {
  await requireStaffSession();
  const id = Number((await params).id);
  if (!Number.isFinite(id)) notFound();

  let application;
  let decisions: DecisionSnapshot[];
  let offers;
  let applicant;
  try {
    application = await getApplication(id);
    [decisions, offers, applicant] = await Promise.all([
      getApplicationDecisions(id),
      getOffers(id),
      getUser(application.userId),
    ]);
  } catch (error) {
    if (error instanceof ApiError && error.isNotFound) notFound();
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Review application" />
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }
  const offer = offers[0];
  const c = application.currency;

  return (
    <>
      <Link
        href="/admin/applications?status=MANUAL_REVIEW"
        className="mb-4 inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
      >
        <ArrowLeft aria-hidden className="size-4" />
        Back to the queue
      </Link>
      <PageHeader
        title={`${productLabel(application.applicationType)} application #${application.id}`}
        description={`Applied ${formatDateTime(application.appliedAt ?? application.createdAt)}`}
        action={<Badge tone={statusTone(application.status)}>{humanise(application.status)}</Badge>}
      />

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title="Applicant" />
          <CardBody>
            <DetailList>
              <Detail label="Customer">
                {applicant.firstName} {applicant.lastName} · #{applicant.id}
              </Detail>
              <Detail label="Identity check">
                <Badge tone={statusTone(applicant.kycStatus)}>{humanise(applicant.kycStatus)}</Badge>
              </Detail>
              <Detail label="Credit score at application">{application.creditScoreAtApply ?? "—"}</Detail>
            </DetailList>
            <Link
              href={`/admin/kyc?userId=${applicant.id}`}
              className="mt-3 inline-flex text-sm font-medium text-primary hover:underline"
            >
              Open identity review
            </Link>
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="What the customer stated" />
          <CardBody>
            <DetailList>
              <Detail label="Requested amount">{money(application.requestedAmount, c)}</Detail>
              <Detail label="Term">{application.termMonths ? `${application.termMonths} months` : "—"}</Detail>
              <Detail label="Purpose">{application.purpose ?? "—"}</Detail>
              <Detail label="Annual income">{money(application.annualIncome, c)}</Detail>
              <Detail label="Monthly debt">{money(application.monthlyDebtObligations, c)}</Detail>
              {application.assetValue !== null ? (
                <Detail label="Asset value">{money(application.assetValue, c)}</Detail>
              ) : null}
              {application.downPayment !== null ? (
                <Detail label="Down payment">{money(application.downPayment, c)}</Detail>
              ) : null}
            </DetailList>
          </CardBody>
        </Card>
      </div>

      <Card className="mt-4">
        <CardHeader
          title="Decision history"
          description="Every decision on this application, oldest first. Nothing here is overwritten."
        />
        {decisions.length === 0 ? (
          <EmptyState title="No decisions recorded" description="The policy has not decided this application." />
        ) : (
          <TableShell label="Decision history">
            <thead>
              <tr>
                <Th>Decided</Th>
                <Th>By</Th>
                <Th>Decision</Th>
                <Th align="right">Score</Th>
                <Th>Identity</Th>
                <Th align="right">DTI</Th>
                <Th align="right">LTV</Th>
                <Th align="right">Approved</Th>
                <Th>Reasons</Th>
              </tr>
            </thead>
            <tbody>
              {decisions.map((d) => (
                <tr key={d.decisionId}>
                  <Td>{formatDateTime(d.decidedAt)}</Td>
                  <Td>
                    {d.decidedBy === "POLICY" ? `Policy ${d.policyVersion}` : `Reviewer #${d.reviewerId ?? "—"}`}
                  </Td>
                  <Td>
                    <Badge tone={d.decision === "APPROVE" ? "positive" : d.decision === "REJECT" ? "critical" : "caution"}>
                      {humanise(d.decision)}
                    </Badge>
                  </Td>
                  <Td align="right">{d.creditScoreAtDecision ?? "—"}</Td>
                  <Td>{d.kycStatusAtDecision ? humanise(d.kycStatusAtDecision) : "—"}</Td>
                  <Td align="right">{ratio(d.dtiAtDecision)}</Td>
                  <Td align="right">{ratio(d.ltvAtDecision)}</Td>
                  <Td align="right">{money(d.approvedAmount, c)}</Td>
                  <Td>
                    {d.reasonCodes.length === 0 ? "—" : (
                      <ul className="grid gap-0.5">
                        {d.reasonCodes.map((code) => (
                          <li key={code}>
                            <code className="text-xs">{code}</code>
                          </li>
                        ))}
                      </ul>
                    )}
                  </Td>
                </tr>
              ))}
            </tbody>
          </TableShell>
        )}
      </Card>

      {offer ? (
        <Card className="mt-4">
          <CardHeader title="Offer" description={`${humanise(offer.status)} · valid until ${formatDateTime(offer.expiresAt)}`} />
          <CardBody>
            <DetailList columns={3}>
              {offer.approvedAmount !== null ? <Detail label="Amount">{money(offer.approvedAmount, offer.currency)}</Detail> : null}
              {offer.creditLimit !== null ? <Detail label="Credit limit">{money(offer.creditLimit, offer.currency)}</Detail> : null}
              {offer.cardTier ? <Detail label="Card">{humanise(offer.cardTier)}</Detail> : null}
              {offer.apr !== null ? <Detail label="APR">{formatPercent(offer.apr)}</Detail> : null}
              {offer.termMonths !== null ? <Detail label="Term">{offer.termMonths} months</Detail> : null}
            </DetailList>
            <p className="mt-3 text-xs text-ink-subtle">Only the customer can accept or decline this offer.</p>
          </CardBody>
        </Card>
      ) : null}

      {/*
       * Always mounted, so the reviewer's confirmation survives the refresh
       * into the decided state; the form offers a decision only while the
       * application is referred.
       */}
      <Card className="mt-4">
        <CardHeader title="Your decision" />
        <CardBody>
          <ReviewDecisionForm
            applicationId={application.id}
            requestedAmount={application.requestedAmount}
            currency={c}
            open={application.status === "MANUAL_REVIEW"}
          />
        </CardBody>
      </Card>
    </>
  );
}
