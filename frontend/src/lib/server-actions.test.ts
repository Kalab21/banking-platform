import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * What a `"use server"` file is allowed to export.
 *
 * Async functions, and nothing else. Export a constant next to the actions and
 * the module throws when it is first loaded — not at build time, not in any
 * unit test that mocks it, and not in an offline suite that never reaches a
 * server action. The first sign is a 500 from a real money movement, which is
 * an expensive place to find out.
 *
 * That is exactly how it was found: the live suite failed on a deposit because
 * `actions.ts` exported an `IDLE` constant alongside its three actions. The
 * states now live in their own modules and this test keeps them there.
 *
 * Deliberately a source scan rather than an import. Importing these modules
 * pulls in `server-only`, which refuses to load under jsdom — so the check that
 * would be most direct is the one that cannot run here.
 */

const SRC = join(process.cwd(), "src");

function sourceFiles(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) return sourceFiles(path);
    return /\.tsx?$/.test(entry) && !/\.test\.tsx?$/.test(entry) ? [path] : [];
  });
}

function useServerFiles(): { path: string; source: string }[] {
  return sourceFiles(SRC)
    .map((path) => ({ path, source: readFileSync(path, "utf8") }))
    .filter(({ source }) => /^\s*["']use server["'];/m.test(source));
}

/** Every `export` in a file, as the keyword that follows it. */
function exportsOf(source: string): { statement: string; line: number }[] {
  return source
    .split("\n")
    .map((text, index) => ({ text, line: index + 1 }))
    .filter(({ text }) => /^export\s/.test(text))
    .map(({ text, line }) => ({ statement: text.trim(), line }));
}

describe('every "use server" module', () => {
  const files = useServerFiles();

  it("is found at all, so this test is not silently vacuous", () => {
    expect(files.length).toBeGreaterThan(0);
  });

  it.each(files.map((f) => [f.path.replace(SRC, "src"), f] as const))(
    "%s exports only async functions",
    (_name, file) => {
      const offenders = exportsOf(file.source).filter(
        ({ statement }) =>
          // A type-only export is erased before it reaches the runtime.
          !/^export\s+type\s/.test(statement) &&
          !/^export\s+interface\s/.test(statement) &&
          !/^export\s+async\s+function\s/.test(statement),
      );

      expect(
        offenders.map((o) => `line ${o.line}: ${o.statement}`),
        'a "use server" file may export async functions and nothing else',
      ).toEqual([]);
    },
  );
});
