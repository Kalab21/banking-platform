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
          className={`flex items-start gap-3 px-5 py-4 sm:gap-4 ${
            n.isRead ? "" : "bg-primary-soft/50"
          }`}
        >
          {/*
           * Unread is carried by a dot, a tinted row, a bolder title and the
           * words "Unread" for a screen reader. A customer who cannot
           * distinguish the tint still has three other signals.
           */}
          <span className="mt-1.5 flex h-2 w-2 shrink-0 items-center justify-center">
            {n.isRead ? null : (
              <span aria-hidden="true" className="h-2 w-2 rounded-full bg-primary" />
            )}
          </span>

          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <p className={`text-sm text-ink ${n.isRead ? "font-medium" : "font-semibold"}`}>
                {n.title}
              </p>
              <Badge tone={n.isRead ? "neutral" : "accent"}>{humanise(n.type)}</Badge>
              {n.isRead ? null : <span className="sr-only">Unread</span>}
            </div>
            <p className="mt-1 text-sm leading-relaxed text-ink-muted">{n.message}</p>
            <p className="mt-1.5 text-xs text-ink-subtle">{formatDateTime(n.createdAt)}</p>
          </div>

          {n.isRead ? null : (
            <div className="shrink-0">
              <MarkReadButton id={n.id} />
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}
