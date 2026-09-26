import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

// apiFetch serializes `body` itself. A caller that stringifies first sends a
// JSON string containing JSON, which Spring cannot bind to its request object.
// Both the application submit and the card freeze shipped that way once.
const SRC = join(__dirname, "..", "..");

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sourceFiles(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

describe("apiFetch request bodies", () => {
  it("are never stringified by the caller", () => {
    const offenders = sourceFiles(SRC)
      .filter((file) => /body:\s*JSON\.stringify/.test(readFileSync(file, "utf8")))
      .map((file) => relative(SRC, file));
    expect(offenders).toEqual([]);
  });
});
