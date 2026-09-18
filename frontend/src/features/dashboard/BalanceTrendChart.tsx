"use client";

import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { formatCompactCurrency, formatCurrency, formatDate, formatDateTime } from "@/lib/format";

export interface BalancePoint {
  /** ISO timestamp of the transaction that produced this balance. */
  at: string;
  /** `balanceAfter` as recorded by the backend — not a computed estimate. */
  balance: number;
}

/**
 * Balance over time for one account.
 *
 * Every point is a real `balanceAfter` value from a persisted transaction, so
 * the line is the account's actual recorded history rather than a projection.
 * The caller decides whether there is enough history to be worth charting; this
 * component only draws what it is given.
 */
export function BalanceTrendChart({
  points,
  currency = "USD",
}: {
  points: BalancePoint[];
  currency?: string;
}) {
  const summary = `Balance moved from ${formatCurrency(points[0]?.balance, currency)} to ${formatCurrency(points[points.length - 1]?.balance, currency)} across ${points.length} transactions.`;

  /*
   * Axis labels follow the span they cover. A demo account's transactions can
   * all land within a minute of each other, and four ticks reading the same
   * date looks like a broken chart rather than a short history — so under two
   * days the axis shows the time instead.
   */
  const first = new Date(points[0]?.at ?? 0).getTime();
  const last = new Date(points[points.length - 1]?.at ?? 0).getTime();
  const withinTwoDays = Number.isFinite(first) && Number.isFinite(last) && last - first < 2 * 86_400_000;

  const axisLabel = (iso: string) =>
    withinTwoDays
      ? new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit" }).format(new Date(iso))
      : formatDate(iso);

  return (
    <figure className="m-0">
      <div className="h-56 w-full" role="img" aria-label={summary}>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={points} margin={{ top: 8, right: 12, bottom: 4, left: 4 }}>
            <defs>
              <linearGradient id="balanceFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--color-accent)" stopOpacity={0.18} />
                <stop offset="100%" stopColor="var(--color-accent)" stopOpacity={0.01} />
              </linearGradient>
            </defs>

            <CartesianGrid stroke="var(--color-line)" vertical={false} />

            <XAxis
              dataKey="at"
              tickFormatter={axisLabel}
              tick={{ fill: "var(--color-ink-subtle)", fontSize: 11 }}
              stroke="var(--color-line-strong)"
              tickLine={false}
              minTickGap={24}
            />
            <YAxis
              tickFormatter={(v: number) => formatCompactCurrency(v, currency)}
              tick={{ fill: "var(--color-ink-subtle)", fontSize: 11 }}
              stroke="var(--color-line-strong)"
              tickLine={false}
              width={72}
            />

            <Tooltip
              formatter={(value) => [formatCurrency(Number(value), currency), "Balance"]}
              labelFormatter={(label) => formatDateTime(String(label))}
              contentStyle={{
                borderRadius: 6,
                border: "1px solid var(--color-line-strong)",
                fontSize: 12,
                color: "var(--color-ink)",
              }}
            />

            <Area
              type="monotone"
              dataKey="balance"
              stroke="var(--color-accent)"
              strokeWidth={2}
              fill="url(#balanceFill)"
              dot={false}
              activeDot={{ r: 4 }}
              isAnimationActive={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
      <figcaption className="mt-2 text-xs text-ink-subtle">
        Recorded balance after each of the last {points.length} transactions on this account.
      </figcaption>
    </figure>
  );
}
