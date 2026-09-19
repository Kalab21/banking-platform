import type { MoneyOutcome } from "@/features/money/outcome";

/**
 * The shape a money form's server action hands back.
 *
 * Kept out of the `"use server"` module on purpose: such a file may export
 * async functions and nothing else, so a constant declared next to the actions
 * makes the whole module fail to load at runtime — and does so only when an
 * action is actually invoked, which no unit test that mocks the module and no
 * offline suite without a backend will ever reach.
 */
export type MoneyFormState =
  | { status: "idle" }
  | { status: "invalid"; fields: Record<string, string> }
  | { status: "settled"; outcome: MoneyOutcome };

export const IDLE: MoneyFormState = { status: "idle" };
