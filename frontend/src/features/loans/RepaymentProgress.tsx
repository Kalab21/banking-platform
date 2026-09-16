"use client";

import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import { formatCurrency } from "@/lib/format";

/**
 * Principal repaid versus principal outstanding.
 *
 * Both figures come straight from the loan record: `principal` and
 * `remainingBalance`. Nothing is estimated.
 */
export function RepaymentProgress({
  principal,
  remainingBalance,
  currency = "USD",
}: {
  principal: number;
  remainingBalance: number;
  currency?: string;
}) {
  const repaid = Math.max(principal - remainingBalance, 0);
  const percent = principal > 0 ? Math.round((repaid / principal) * 100) : 0;

  const data = [
    { name: "Repaid", value: repaid, fill: "var(--color-accent)" },
    { name: "Outstanding", value: Math.max(remainingBalance, 0), fill: "var(--color-line)" },
  ];

  return (
    <figure className="m-0 flex items-center gap-5">
      <div
        className="h-32 w-32 shrink-0"
        role="img"
        aria-label={`${percent}% of principal repaid: ${formatCurrency(repaid, currency)} of ${formatCurrency(principal, currency)}.`}
      >
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              dataKey="value"
              innerRadius="64%"
              outerRadius="100%"
              startAngle={90}
              endAngle={-270}
              stroke="none"
              isAnimationActive={false}
            >
              {data.map((entry) => (
                <Cell key={entry.name} fill={entry.fill} />
              ))}
            </Pie>
            <Tooltip
              formatter={(value, name) => [formatCurrency(Number(value), currency), String(name)]}
              contentStyle={{
                borderRadius: 6,
                border: "1px solid var(--color-line-strong)",
                fontSize: 12,
              }}
            />
          </PieChart>
        </ResponsiveContainer>
      </div>

      <figcaption className="min-w-0 space-y-1 text-sm">
        <p className="tabular text-2xl font-semibold text-ink">{percent}%</p>
        <p className="text-ink-muted">of principal repaid</p>
        <p className="text-xs text-ink-subtle">
          {formatCurrency(repaid, currency)} of {formatCurrency(principal, currency)}
        </p>
      </figcaption>
    </figure>
  );
}
