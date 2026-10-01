import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { buildProfileEditor, noticePath, toolDirectory } from "./bundle.mjs";

await buildProfileEditor();

// A dependency update changes the locked runtime versions, and check.mjs requires NOTICE.txt to list exactly
// those versions, so the build rewrites the inventory lines from package-lock.json instead of leaving it by hand.
const packageLock = JSON.parse(await readFile(resolve(toolDirectory, "package-lock.json"), "utf8"));
const inventory = Object.entries(packageLock.packages)
  .filter(([path, locked]) => path.startsWith("node_modules/") && !locked.dev)
  .map(([path, locked]) => `${path.slice("node_modules/".length)} ${locked.version}`)
  .sort((left, right) => left.split(" ")[0] < right.split(" ")[0] ? -1 : left.split(" ")[0] > right.split(" ")[0] ? 1 : 0);
const inventoryLine = /^(?:@[^ ]+|[^@ ][^ ]*) \d+\.\d+\.\d+/;
const lines = (await readFile(noticePath, "utf8")).split("\n");
const first = lines.findIndex((line) => inventoryLine.test(line));
const last = lines.findLastIndex((line) => inventoryLine.test(line));
if (first < 0) throw new Error("NOTICE.txt has no package inventory to refresh");
lines.splice(first, last - first + 1, ...inventory);
await writeFile(noticePath, lines.join("\n"));
