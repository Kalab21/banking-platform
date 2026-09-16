import type { Metadata } from "next";
import { requireStaffSession } from "@/lib/session";
import { getKycDocuments, getUser } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import {
  Badge,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  ErrorState,
  PageHeader,
  statusTone,
} from "@/components/ui/primitives";
import { humanise } from "@/lib/format";
import { KycReviewList } from "@/features/admin/KycReviewPanel";

export const metadata: Metadata = { title: "KYC review" };

/**
 * Staff KYC review.
 *
 * The backend exposes KYC documents per user (`/api/users/{userId}/kyc/documents`)
 * and a review endpoint, but there is no "all pending documents" query. Rather
 * than invent a queue, this page looks a customer up by id and reviews what they
 * have submitted.
 */
export default async function AdminKycPage({
  searchParams,
}: {
  searchParams: Promise<{ userId?: string }>;
}) {
  await requireStaffSession();
  const { userId: rawUserId } = await searchParams;
  const userId = rawUserId ? Number(rawUserId) : null;

  let profile = null;
  let documents = null;
  let error: string | null = null;

  if (userId !== null && Number.isFinite(userId)) {
    try {
      [profile, documents] = await Promise.all([getUser(userId), getKycDocuments(userId)]);
    } catch (e) {
      if (e instanceof ApiError && e.isNotFound) error = `No customer found with id ${userId}.`;
      else if (e instanceof ApiError || e instanceof NetworkError) error = e.userMessage;
      else throw e;
    }
  }

  return (
    <>
      <PageHeader
        title="KYC review"
        description="Look up a customer and decide on the documents they have submitted."
      />

      <Card>
        <CardHeader title="Find a customer" />
        <CardBody>
          <form method="GET" className="flex flex-wrap items-end gap-3">
            <div>
              <label htmlFor="userId" className="block text-sm font-medium text-ink">
                Customer ID
              </label>
              <input
                id="userId"
                name="userId"
                type="number"
                min={1}
                defaultValue={rawUserId ?? ""}
                required
                className="mt-1.5 w-48 rounded-md border border-line-strong bg-surface px-3 py-2 text-sm text-ink"
              />
            </div>
            <button
              type="submit"
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent-hover"
            >
              Look up
            </button>
          </form>
          <p className="mt-3 text-xs text-ink-subtle">
            The platform has no bulk &ldquo;pending documents&rdquo; endpoint, so review is per
            customer.
          </p>
        </CardBody>
      </Card>

      {error ? <ErrorState message={error} /> : null}

      {profile && documents ? (
        <Card>
          <CardHeader
            title={`${profile.firstName} ${profile.lastName}`}
            description={`@${profile.username} · customer #${profile.id}`}
            action={<Badge tone={statusTone(profile.kycStatus)}>{humanise(profile.kycStatus)}</Badge>}
          />
          {documents.length === 0 ? (
            <EmptyState
              title="No documents submitted"
              description="This customer has not sent anything for verification yet."
            />
          ) : (
            <KycReviewList documents={documents} />
          )}
        </Card>
      ) : null}
    </>
  );
}
