import type { Metadata } from "next";
import { requireSession } from "@/lib/session";
import { getUser } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { ErrorState, PageHeader } from "@/components/ui/primitives";
import { WelcomePanel } from "@/features/onboarding/WelcomePanel";

export const metadata: Metadata = { title: "Welcome" };

/**
 * Where onboarding ends.
 *
 * A route of its own rather than a final panel inside the wizard, because by
 * this point a session exists and the signed-out pages redirect anyone holding
 * one. It reads the profile back from the server, so what it confirms is what
 * was stored.
 */
export default async function WelcomePage() {
  const session = await requireSession();

  let profile;
  try {
    profile = await getUser(session.userId);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      // The account exists — the session proves it — so this is a failure to
      // read the profile back, not a failure to create the account. Said
      // plainly rather than implying the registration did not happen.
      return (
        <>
          <PageHeader
            title="Your account is open"
            description="We could not load your profile just now."
          />
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  return (
    <WelcomePanel
      firstName={profile.firstName}
      ssnLast4={profile.ssnLast4}
      identityStatus={profile.identityStatus}
    />
  );
}
