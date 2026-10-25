// The browser half of the end-to-end test (passwords-browser step 7): a login page, Chromium with
// the built extension, the real `unitool browser host` that `unitool browser install` set up, and
// the real Ctrl+Shift+L through xdotool — so activeTab is granted the way a person grants it.
// It takes commands as JSON lines on stdin and reports as JSON lines on stdout; the JVM test is
// the phone and says what to do. Run under Xvfb: `xvfb-run -a node e2e/drive.mjs …`.
import { createServer } from "node:http";
import { execFileSync } from "node:child_process";
import { createInterface } from "node:readline";
import puppeteer from "puppeteer-core";

const args = Object.fromEntries(process.argv.slice(2).map((a) => a.replace(/^--/, "").split("=")));
const report = (o) => process.stdout.write(JSON.stringify(o) + "\n");
const fail = (e) => {
  report({ ev: "error", message: String(e?.stack ?? e) });
  process.exit(1);
};

const page = `<!doctype html><title>Sign in</title><form id=f onsubmit="window.submitted=true;return false">
<input id=search type=search placeholder=Search>
<label>Login <input id=user name=login autocomplete=username></label>
<label>Password <input id=pass type=password name=password></label><button>Sign in</button></form>
<script>window.submitted=false</script>`;
const server = createServer((_req, res) => {
  res.writeHead(200, { "content-type": "text/html" });
  res.end(page);
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const site = `http://127.0.0.1:${server.address().port}/login`;

const browser = await puppeteer.launch({
  executablePath: args.chromium,
  headless: false,
  userDataDir: args.profile,
  env: { ...process.env, HOME: args.home },
  args: [
    `--load-extension=${args.ext}`,
    `--disable-extensions-except=${args.ext}`,
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-gpu",
    "--window-size=1200,800",
    "--password-store=basic",
  ],
  defaultViewport: null,
}).catch(fail);

const tab = (await browser.pages())[0] ?? (await browser.newPage());
await tab.goto(site);
report({ ev: "ready", site });

async function shortcut() {
  await tab.bringToFront();
  const pid = browser.process().pid;
  // The window of this Chromium, focused at the X level, then the real key press.
  const ids = execFileSync("xdotool", ["search", "--sync", "--onlyvisible", "--pid", String(pid)], { encoding: "utf8" }).trim().split("\n");
  const win = ids[ids.length - 1];
  execFileSync("xdotool", ["windowfocus", "--sync", win]);
  execFileSync("xdotool", ["key", "--window", win, "--clearmodifiers", "ctrl+shift+l"]);
  const target = await browser.waitForTarget((t) => t.url().endsWith("/popup/popup.html"), { timeout: 10_000 });
  return await target.asPage();
}

const screen = (popup) => popup.evaluate(() => document.getElementById("app")?.dataset.screen ?? "");
async function until(popup, wanted, ms = 90_000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const now = await screen(popup).catch(() => "");
    if (wanted.includes(now)) return now;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`popup never showed ${wanted}; it shows ${await screen(popup).catch(() => "?")}`);
}

const lines = createInterface({ input: process.stdin });
for await (const line of lines) {
  try {
    const c = JSON.parse(line);
    if (c.cmd === "pair") {
      const popup = await shortcut();
      await until(popup, ["idle"]);
      await popup.waitForSelector("#pair", { timeout: 15_000 });
      await popup.click("#pair");
      await until(popup, ["pairing"]);
      const img = await popup.waitForSelector("img.qr");
      await new Promise((r) => setTimeout(r, 300));
      const png = await img.screenshot({ encoding: "base64" });
      const text = await popup.evaluate(() => document.getElementById("app").innerText);
      report({ ev: "qr", png, text });
      const end = await until(popup, ["paired", "error"], 120_000);
      report({ ev: end, text: await popup.evaluate(() => document.getElementById("app").innerText) });
      await popup.close().catch(() => undefined);
    } else if (c.cmd === "fill") {
      await tab.goto(site);
      const popup = await shortcut();
      const seen = [];
      const end = Date.now() + 120_000;
      let last = "";
      while (Date.now() < end) {
        const s = await screen(popup).catch(() => "");
        if (s && s !== last) {
          seen.push(s);
          last = s;
          report({ ev: "screen", screen: s });
        }
        if (["filled", "denied", "timeout", "error", "copy", "cancelled"].includes(s)) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      const values = await tab.evaluate(() => ({
        user: document.getElementById("user").value,
        pass: document.getElementById("pass").value,
        search: document.getElementById("search").value,
        submitted: window.submitted,
      }));
      const text = await popup.evaluate(() => document.getElementById("app").innerText).catch(() => "");
      report({ ev: "result", screens: seen, values, text });
      await popup.close().catch(() => undefined);
    } else if (c.cmd === "quit") {
      break;
    }
  } catch (e) {
    report({ ev: "error", message: String(e?.stack ?? e) });
  }
}
await browser.close();
server.close();
report({ ev: "bye" });
