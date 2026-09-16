"use client";

import { useActionState } from "react";
import {
  markAllReadAction,
  markReadAction,
  type NotificationActionState,
} from "@/features/notifications/actions";
import { Button, FormError } from "@/components/ui/form";
import { Badge, EmptyState } from "@/components/ui/primitives";
import { formatDateTime, humanise } from "@/lib/format";
import type { Notification } from "@/types/api";

const INITIAL: NotificationActionState = {};

function MarkReadButton({ id }: { id: number }) {
  const [state, action, pending] = useActionState(markReadAction, INITIAL);

  return (
    <form action={action}>
      <input type="hidden" name="id" value={id} />
      <Button type="submit" variant="ghost" pending={pending} className="px-2 py-1 text-xs">
        {pending ? "Marking…" : "Mark read"}
      </Button>
      {state.error ? <span className="sr-only">{state.error}</span> : null}
    </form>
  );
}

export function MarkAllReadButton({ disabled }: { disabled: boolean }) {
  const [state, action, pending] = useActionState(markAllReadAction, INITIAL);

  return (
    <div className="space-y-2">
      <form action={action}>
        <Button type="submit" variant="secondary" pending={pending} disabled={disabled}>
          {pending ? "Updating…" : "Mark all read"}
        </Button>
      </form>
      {state.error ? <FormError>{state.error}</FormError> : null}
    </div>
  );
}

export function NotificationList({ notifications }: { notifications: Notification[] }) {
  if (notifications.length === 0) {
    return (
      <EmptyState
        title="No alerts"
        description="Account activity, payment outcomes and security alerts appear here."
      />
    );
  }

  return (
    <ul className="divide-y divide-line">
      {notifications.map((n) => (
        <li
          key={n.id}
          className={`flex items-start justify-between gap-4 px-5 py-4 ${
            n.isRead ? "" : "bg-accent-soft/40"
          }`}
        >
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <p className="text-sm font-medium text-ink">{n.title}</p>
              <Badge tone={n.isRead ? "neutral" : "accent"}>{humanise(n.type)}</Badge>
              {n.isRead ? null : <span className="sr-only">Unread</span>}
            </div>
            <p className="mt-1 text-sm text-ink-muted">{n.message}</p>
            <p className="mt-1 text-xs text-ink-subtle">{formatDateTime(n.createdAt)}</p>
          </div>
          {n.isRead ? null : <MarkReadButton id={n.id} />}
        </li>
      ))}
    </ul>
  );
}
