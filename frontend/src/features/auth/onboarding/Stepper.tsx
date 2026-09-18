"use client";

import { Check } from "lucide-react";
import { STEPS, type StepId, stepIndex } from "@/features/auth/onboarding/steps";

/**
 * Where the customer is in onboarding.
 *
 * Two presentations of one thing. On a wide screen the five steps sit side by
 * side, because there is room and seeing the whole shape of the process is
 * reassuring. On a phone there is not room for five labels, and shrinking them
 * until they fit produces five illegible words — so it becomes a progress bar
 * and a sentence.
 *
 * Neither is a navigation control. Steps are not clickable: jumping to the
 * address step before the account step has been filled in would produce a form
 * that cannot be submitted, and the review screen already offers a way back to
 * any step.
 */
export function Stepper({ current }: { current: StepId }) {
  const currentIndex = stepIndex(current);
  const total = STEPS.length;

  return (
    <div>
      {/* Phone: position, name and a bar. */}
      <div className="sm:hidden">
        <div className="flex items-baseline justify-between">
          <p className="text-sm font-medium text-ink">{STEPS[currentIndex].label}</p>
          <p className="text-[0.8125rem] text-ink-muted">
            Step {currentIndex + 1} of {total}
          </p>
        </div>
        <div
          className="mt-2 h-1.5 overflow-hidden rounded-full bg-surface-subtle"
          role="progressbar"
          aria-valuemin={1}
          aria-valuemax={total}
          aria-valuenow={currentIndex + 1}
          aria-valuetext={`Step ${currentIndex + 1} of ${total}: ${STEPS[currentIndex].label}`}
        >
          <div
            className="h-full rounded-full bg-primary transition-[width] duration-300"
            style={{ width: `${((currentIndex + 1) / total) * 100}%` }}
          />
        </div>
      </div>

      {/* Desktop: the whole process at once. */}
      <ol className="hidden sm:flex sm:items-center" aria-label={`Step ${currentIndex + 1} of ${total}`}>
        {STEPS.map((step, index) => {
          const done = index < currentIndex;
          const active = index === currentIndex;
          return (
            <li
              key={step.id}
              className={index === total - 1 ? "flex items-center" : "flex flex-1 items-center"}
              aria-current={active ? "step" : undefined}
            >
              <div className="flex flex-col items-center gap-1.5">
                <span
                  aria-hidden="true"
                  className={[
                    "flex h-7 w-7 items-center justify-center rounded-full text-xs font-semibold transition-colors",
                    done
                      ? "bg-primary text-white"
                      : active
                        ? "bg-primary/10 text-primary ring-2 ring-inset ring-primary"
                        : "bg-surface-subtle text-ink-subtle ring-1 ring-inset ring-line",
                  ].join(" ")}
                >
                  {done ? <Check className="h-3.5 w-3.5" /> : index + 1}
                </span>
                <span
                  className={[
                    "text-[0.75rem] leading-none",
                    active ? "font-medium text-ink" : "text-ink-subtle",
                  ].join(" ")}
                >
                  {step.label}
                </span>
              </div>

              {index < total - 1 ? (
                <span
                  aria-hidden="true"
                  className={[
                    "mx-2 -mt-5 h-px flex-1 transition-colors",
                    done ? "bg-primary" : "bg-line",
                  ].join(" ")}
                />
              ) : null}
            </li>
          );
        })}
      </ol>
    </div>
  );
}
