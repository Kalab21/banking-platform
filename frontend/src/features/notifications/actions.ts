"use server";

import { revalidatePath } from "next/cache";
import { markAllNotificationsRead, markNotificationRead } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { requireSession } from "@/lib/session";

export interface NotificationActionState {
  error?: string;
}

export async function markReadAction(
  _prev: NotificationActionState,
  formData: FormData,
): Promise<NotificationActionState> {
  // Still called for the gate it applies: an unauthenticated caller never
  // reaches the request. The id it returns is no longer needed, because the
  // service marks the caller's own notification.
  await requireSession();
  const id = Number(formData.get("id"));
  if (!Number.isFinite(id)) return { error: "That alert could not be identified." };

  try {
    await markNotificationRead(id);
    revalidatePath("/notifications");
    revalidatePath("/dashboard");
    return {};
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return { error: error.userMessage };
    }
    return { error: "That alert could not be updated." };
  }
}

export async function markAllReadAction(
  _prev: NotificationActionState,
): Promise<NotificationActionState> {
  const session = await requireSession();

  try {
    await markAllNotificationsRead(session.userId);
    revalidatePath("/notifications");
    revalidatePath("/dashboard");
    return {};
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return { error: error.userMessage };
    }
    return { error: "Your alerts could not be updated." };
  }
}
