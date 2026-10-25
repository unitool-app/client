import { beforeEach, describe, expect, it } from "vitest";
import { Background, COPY_MILLIS, type Api, type Env, type Port } from "../src/core";
import { browserLabel, originOf, type HostMessage, type PopupCommand, type View } from "../src/protocol";

/** A port with both ends in the test. */
function pair<In, Out>() {
  const toBg: ((m: In) => void)[] = [];
  const disc: ((p: { error?: { message?: string } }) => void)[] = [];
  const sent: Out[] = [];
  let disconnected = false;
  const port: Port<In, Out> = {
    postMessage: (m) => void sent.push(m),
    disconnect: () => void (disconnected = true),
    onMessage: { addListener: (f) => void toBg.push(f) },
    onDisconnect: { addListener: (f) => void disc.push(f) },
  };
  return {
    port,
    sent,
    get disconnected() {
      return disconnected;
    },
    say: (m: In) => toBg.forEach((f) => f(m)),
    drop: (error?: string) => disc.forEach((f) => f({ error: error ? { message: error } : undefined })),
  };
}

let natives: ReturnType<typeof pair<HostMessage, unknown>>[];
let popupListeners: ((p: Port<PopupCommand, View | { copied: string }>) => void)[];
let tabs: Record<number, string>;
let injected: { args: [string, string, string]; tabId: number }[];
let injectResult: string;
let stored: Record<string, unknown>[];
let oneShot: (m: { type: string }) => HostMessage;
let timers: { fn: () => void; at: number }[];
let clock: number;

const env: Env = {
  now: () => clock,
  setTimeout: (fn, ms) => timers.push({ fn, at: clock + ms }),
  clearTimeout: () => undefined,
  navigator: { userAgent: "Mozilla/5.0 (X11; Linux x86_64) Chrome/145.0 Safari/537.36", userAgentData: { brands: [{ brand: "Chromium", version: "145" }], platform: "Linux" } },
};

function api(): Api {
  return {
    runtime: {
      connectNative: () => {
        const p = pair<HostMessage, unknown>();
        natives.push(p);
        return p.port;
      },
      sendNativeMessage: async (_h, m) => oneShot(m as { type: string }),
      onConnect: { addListener: (f) => void popupListeners.push(f) },
    },
    tabs: { get: async (id) => ({ url: tabs[id] }) },
    scripting: {
      executeScript: async (inj) => {
        injected.push({ args: inj.args, tabId: inj.target.tabId });
        return [{ result: injectResult === "filled" ? { result: "filled", login: true } : { result: "not_found" } }];
      },
    },
    storage: { local: { set: async (items) => void stored.push(items) } },
  };
}

function popup(bg: Background) {
  const p = pair<PopupCommand, View | { copied: string }>();
  popupListeners.forEach((f) => f(p.port));
  return { ...p, last: () => p.sent.filter((m) => !("copied" in m)).at(-1) as View, bg };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

beforeEach(() => {
  natives = [];
  popupListeners = [];
  tabs = { 7: "https://github.com/login" };
  injected = [];
  injectResult = "filled";
  stored = [];
  oneShot = () => ({ type: "status", paired: true, phones: 1 });
  timers = [];
  clock = 1_000;
});

describe("the background (README §7)", () => {
  it("asks for the tab's origin, relays the states and fills only that tab", async () => {
    const bg = new Background(api(), env);
    const ui = popup(bg);
    await bg.command(ui.port, { cmd: "fill", tabId: 7 });
    const host = natives[0]!;
    expect(host.sent).toEqual([{ type: "fill", origin: "https://github.com", browser: "Chromium 145 · Linux" }]);
    host.say({ type: "state", state: "searching" });
    expect(ui.last()).toMatchObject({ screen: "searching", site: "github.com" });
    host.say({ type: "state", state: "shown" });
    host.say({ type: "fill", username: "octocat", password: "s3cret", phone: "Pixel" });
    await flush();
    expect(injected).toEqual([{ tabId: 7, args: ["octocat", "s3cret", "https://github.com"] }]);
    expect(ui.last()).toEqual({ screen: "filled", site: "github.com", username: "octocat", phone: "Pixel" });
    expect(host.disconnected).toBe(true);
    // The password reached the page and nowhere else: not the popup, not storage.
    expect(JSON.stringify(ui.sent)).not.toContain("s3cret");
    expect(JSON.stringify(stored)).not.toContain("s3cret");
    expect(bg.holding).toBe(false);
  });

  it("does not fill a tab that has moved to another site, and offers to copy instead", async () => {
    const bg = new Background(api(), env);
    const ui = popup(bg);
    await bg.command(ui.port, { cmd: "fill", tabId: 7 });
    tabs[7] = "https://github-login.example/";
    natives[0]!.say({ type: "fill", username: "octocat", password: "s3cret" });
    await flush();
    expect(injected).toEqual([]);
    expect(ui.last()).toMatchObject({ screen: "copy", why: "tab_changed", username: "octocat" });
    expect(JSON.stringify(ui.last())).not.toContain("s3cret");
    await bg.command(ui.port, { cmd: "copy", what: "password" });
    expect(ui.sent.at(-1)).toEqual({ copied: "s3cret" });
    expect(bg.holding).toBe(false);
  });

  it("holds a login no field took for a minute, then forgets it", async () => {
    injectResult = "not_found";
    const bg = new Background(api(), env);
    const ui = popup(bg);
    await bg.command(ui.port, { cmd: "fill", tabId: 7 });
    natives[0]!.say({ type: "fill", username: "u", password: "p" });
    await flush();
    expect(ui.last()).toMatchObject({ screen: "copy", why: "no_field" });
    expect(bg.holding).toBe(true);
    expect(timers.at(-1)!.at).toBe(clock + COPY_MILLIS);
    timers.at(-1)!.fn();
    expect(bg.holding).toBe(false);
    expect(ui.last()).toMatchObject({ screen: "idle" });
  });

  it("cancel closes the native port; deny, timeout and errors reach the popup", async () => {
    const bg = new Background(api(), env);
    const ui = popup(bg);
    await bg.command(ui.port, { cmd: "fill", tabId: 7 });
    await bg.command(ui.port, { cmd: "cancel" });
    expect(natives[0]!.disconnected).toBe(true);
    expect(ui.last()).toEqual({ screen: "cancelled" });
    await bg.command(ui.port, { cmd: "fill", tabId: 7 });
    natives[1]!.say({ type: "done", decision: "deny" });
    expect(ui.last()).toMatchObject({ screen: "denied" });
    await bg.command(ui.port, { cmd: "fill", tabId: 7 });
    natives[2]!.say({ type: "error", code: "no-phone" });
    expect(ui.last()).toMatchObject({ screen: "error", code: "no-phone" });
  });

  it("a host that is not installed is an error the popup can explain", async () => {
    const bg = new Background(api(), env);
    const ui = popup(bg);
    await bg.command(ui.port, { cmd: "fill", tabId: 7 });
    natives[0]!.drop("Specified native messaging host not found.");
    expect(ui.last()).toMatchObject({ screen: "error", code: "host", message: "Specified native messaging host not found." });
    oneShot = () => {
      throw new Error("No such native application cx.m42.unitool");
    };
    await bg.command(ui.port, { cmd: "reset" });
    expect(ui.last()).toMatchObject({ screen: "idle", status: { reachable: false } });
  });

  it("refuses pages that are not web pages without starting the host", async () => {
    tabs[7] = "chrome://settings";
    const bg = new Background(api(), env);
    const ui = popup(bg);
    await bg.command(ui.port, { cmd: "fill", tabId: 7 });
    expect(natives).toEqual([]);
    expect(ui.last()).toMatchObject({ screen: "error", code: "origin" });
  });

  it("pairing shows the host's code and ends on paired", async () => {
    const bg = new Background(api(), env);
    const ui = popup(bg);
    await bg.command(ui.port, { cmd: "pair" });
    expect(natives[0]!.sent).toEqual([{ type: "pair" }]);
    natives[0]!.say({ type: "pair", uri: "unitool-pair:1:x", svg: "<svg/>", fingerprint: "SHA256:abc", computer: "laptop", expires: 99 });
    expect(ui.last()).toMatchObject({ screen: "pairing", fingerprint: "SHA256:abc", computer: "laptop" });
    natives[0]!.say({ type: "paired", phone: "Pixel" });
    expect(ui.last()).toEqual({ screen: "paired", phone: "Pixel" });
  });

  it("a popup opened after an operation ended starts afresh, one opened during it does not", async () => {
    const bg = new Background(api(), env);
    const first = popup(bg);
    await bg.command(first.port, { cmd: "pair" });
    natives[0]!.say({ type: "paired", phone: "Pixel" });
    first.drop();
    const second = popup(bg);
    expect(second.sent[0]).toEqual({ screen: "idle" });
    await bg.command(second.port, { cmd: "fill", tabId: 7 });
    natives[1]!.say({ type: "state", state: "shown" });
    second.drop();
    const third = popup(bg);
    expect(third.sent[0]).toMatchObject({ screen: "shown" });
  });

  it("storage holds settings only", async () => {
    const bg = new Background(api(), env);
    const ui = popup(bg);
    await bg.command(ui.port, { cmd: "view" });
    expect(stored).toEqual([{ v: 1, paired: true }]);
  });
});

describe("protocol helpers", () => {
  it("names the browser as the phone shows it", () => {
    expect(browserLabel({ userAgent: "Mozilla/5.0 (X11; Linux x86_64; rv:146.0) Gecko/20100101 Firefox/146.0" })).toBe("Firefox 146 · Linux");
    expect(browserLabel({ userAgent: "x Chrome/141.0", userAgentData: { brands: [{ brand: "Not=A?Brand", version: "24" }, { brand: "Google Chrome", version: "141" }], platform: "Linux" } })).toBe("Chrome 141 · Linux");
  });
  it("takes only web origins", () => {
    expect(originOf("https://github.com/login?x=1#y")).toBe("https://github.com");
    expect(originOf("http://localhost:8080/")).toBe("http://localhost:8080");
    expect(originOf("file:///etc/passwd")).toBeNull();
    expect(originOf("about:blank")).toBeNull();
    expect(originOf(undefined)).toBeNull();
  });
});
