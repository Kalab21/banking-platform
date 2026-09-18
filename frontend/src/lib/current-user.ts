import "server-only";

import { cache } from "react";
import { getUser } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import type { UserProfile } from "@/types/api";

/**
 * The signed-in customer's profile, fetched at most once per request.
 *
 * The shell wants a real name for the sidebar, and the dashboard and profile
 * pages want the whole record. Those render in the same request, and the API
 * client sends `cache: "no-store"`, so Next's own fetch de-duplication does not
 * apply — without `cache()` the layout would add a second identical call to
 * every page load.
 *
 * `cache()` memoises per request, not across requests: two customers never
 * share a result, and a reload always re-reads.
 */
export const getCurrentUser = cache(async (userId: number): Promise<UserProfile> => {
  return getUser(userId);
});

/**
 * The same profile, but a failure yields null instead of throwing.
 *
 * For the shell only. A profile call that fails should cost the customer their
 * name in the sidebar, not the page they asked for — the layout wraps every
 * route, so throwing here would replace working content with an error screen.
 * Pages that need the profile to render call `getCurrentUser` and handle the
 * failure themselves.
 */
export async function getCurrentUserOrNull(userId: number): Promise<UserProfile | null> {
  try {
    return await getCurrentUser(userId);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) return null;
    throw error;
  }
}
