"use client";

import { Check, Circle } from "lucide-react";
import { PASSWORD_RULES } from "@/lib/validation";

/**
 * Live checklist of the password rules.
 *
 * Every rule shown is a rule the backend actually enforces — the list and the
 * schema read the same array, so the customer is never told to satisfy
 * something the server ignores, or allowed to miss something it does not.
 *
 * Deliberately not a strength meter. A score bar implies a judgement the system
 * is not making, and cannot tell anyone what to change.
 *
 * Announced politely rather than assertively: a screen-reader user typing a
 * password should hear rules being met without each keystroke interrupting.
 */
export function PasswordRequirements({ value, id }: { value: string; id?: string }) {
  const met = PASSWORD_RULES.filter((rule) => rule.test(value)).length;

  return (
    <div id={id} className="mt-2.5">
      <p className="sr-only" role="status" aria-live="polite">
        {met} of {PASSWORD_RULES.length} password requirements met
      </p>
      <ul className="grid gap-1.5 sm:grid-cols-2">
        {PASSWORD_RULES.map((rule) => {
          const satisfied = rule.test(value);
          return (
            <li
              key={rule.id}
              className={`flex items-center gap-1.5 text-[0.8125rem] ${
                satisfied ? "text-positive" : "text-ink-subtle"
              }`}
            >
              {/*
               * The icon changes shape as well as colour, so the state is
               * legible without relying on green against grey.
               */}
              {satisfied ? (
                <Check aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
              ) : (
                <Circle aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
              )}
              <span>{rule.label}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
