// Builds dist/chrome and dist/firefox from one source (twofa-browser 03 §1): the manifests are
// manifest/base.json with chrome.json or firefox.json on top. `--zip` also packs them for the stores.
import { build } from "esbuild";
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";

const zip = process.argv.includes("--zip");
const base = JSON.parse(readFileSync("manifest/base.json", "utf8"));

for (const target of ["chrome", "firefox"]) {
  const out = `dist/${target}`;
  rmSync(out, { recursive: true, force: true });
  mkdirSync(`${out}/popup`, { recursive: true });
  await build({
    entryPoints: { background: "src/background.ts", "popup/popup": "src/popup/popup.ts" },
    outdir: out,
    bundle: true,
    format: "esm",
    target: target === "chrome" ? "chrome116" : "firefox128",
    minify: false,
    legalComments: "none",
    logLevel: "warning",
  });
  cpSync("src/popup/popup.html", `${out}/popup/popup.html`);
  cpSync("src/popup/popup.css", `${out}/popup/popup.css`);
  cpSync("_locales", `${out}/_locales`, { recursive: true });
  cpSync("icons", `${out}/icons`, { recursive: true });
  const manifest = { ...base, ...JSON.parse(readFileSync(`manifest/${target}.json`, "utf8")) };
  writeFileSync(`${out}/manifest.json`, JSON.stringify(manifest, null, 2) + "\n");
  if (zip) {
    // The Chrome Web Store rejects a "key"; the store gives its own id (D567).
    if (target === "chrome") {
      const store = { ...manifest };
      delete store.key;
      writeFileSync(`${out}/manifest.json`, JSON.stringify(store, null, 2) + "\n");
    }
    const file = `../unitool-${target}-${manifest.version}.${target === "chrome" ? "zip" : "xpi"}`;
    rmSync(`dist/${file.slice(3)}`, { force: true });
    execFileSync("zip", ["-qr", "-X", file, "."], { cwd: out });
    writeFileSync(`${out}/manifest.json`, JSON.stringify(manifest, null, 2) + "\n");
  }
}
console.log("built dist/chrome and dist/firefox");
