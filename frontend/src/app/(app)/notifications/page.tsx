import type { Metadata } from "next";
import { requireSession } from "@/lib/session";
import { getNotifications } from "@/lib/api/banking";
import { ApiError, NetworkError } from "@/lib/api/client";
import { Card, CardHeader, ErrorState, PageHeader } from "@/components/ui/primitives";
import { MarkAllReadButton, NotificationList } from "@/features/notifications/NotificationList";

export const metadata: Metadata = { title: "Notifications" };

export default async function NotificationsPage() {
  const session = await requireSession();

  let data;
  try {
    data = await getNotifications(session.userId, 0, 50);
  } catch (error) {
    if (error instanceof ApiError || error instanceof NetworkError) {
      return (
        <>
          <PageHeader title="Notifications" />
          <ErrorState message={error.userMessage} />
        </>
      );
    }
    throw error;
  }

  return (
    <>
      <PageHeader
        title="Notifications"
        description={
          data.unreadCount > 0
            ? `${data.unreadCount} unread of ${data.totalElements}`
            : `${data.totalElements} in total`
        }
        action={<MarkAllReadButton disabled={data.unreadCount === 0} />}
      />

      <Card>
        <CardHeader title="Alerts" description="Driven by events published across the platform." />
        <NotificationList notifications={data.notifications} />
      </Card>
    </>
  );
}
