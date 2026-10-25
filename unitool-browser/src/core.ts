import { fillCredentials, type FillResult } from "./fill";
import { HOST, browserLabel, originOf, type HostMessage, type HostStatus, type PopupCommand, type View } from "./protocol";

/** The parts of `chrome.*` / `browser.*` the background uses — a test hands its own. */
export interface Port<In, Out> {
  postMessage(message: Out): void;
  disconnect(): void;
  onMessage: { addListener(listener: (message: In) => void): void };
  onDisconnect: { addListener(listener: (port: { error?: { message?: string } }) => void): void };
  name?: string;
}

export interface Api {
  runtime: {
    connectNative(application: string): Port<HostMessage, unknown>;
    sendNativeMessage(application: string, message: unknown): Promise<HostMessage>;
    onConnect: { addListener(listener: (port: Port<PopupCommand, View | { copied: string }>) => void): void };
    lastError?: { message?: string } | null;
  };
  tabs: { get(tabId: number): Promise<{ url?: string }> };
  scripting: {
    executeScript(injection: {
      target: { tabId: number; allFrames: boolean };
      func: typeof fillCredentials;
      args: [string, string, string];
    }): Promise<{ result?: FillResult }[]>;
  };
  storage: { local: { set(items: Record<string, unknown>): Promise<void> } };
}

export interface Env {
  now(): number;
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(id: unknown): void;
  navigator: { userAgent: string; userAgentData?: { brands?: { brand: string; version: string }[]; platform?: string } };
}

/** How long a login that could not be filled waits for "Copy" (README §7). */
export const COPY_MILLIS = 60_000;

/**
 * The background (README §7, D535, D549): one operation at a time over one native port. The
 * password lives here, in this variable, from the host's answer to the page — or, when no field took
 * it, until "Copy" or a minute. Never in `storage`, never in a log, never in a message to the popup
 * other than the answer to its own click.
 */
export class Background {
  private view: View = { screen: "idle" };
  private popups = new Set<Port<PopupCommand, View | { copied: string }>>();
  private native: Port<HostMessage, unknown> | null = null;
  private job: { tabId: number; origin: string; site: string } | null = null;
  private held: { username: string; password: string; timer: unknown } | null = null;

  constructor(private readonly api: Api, private readonly env: Env) {
    api.runtime.onConnect.addListener((port) => this.connect(port));
  }

  private get firefox(): boolean {
    return /Firefox\//.test(this.env.navigator.userAgent);
  }

  private connect(port: Port<PopupCommand, View | { copied: string }>) {
    // A popup opened after an operation ended starts afresh: opening it is the next request (README §7).
    // Only a running operation and a login waiting for "Copy" are shown again.
    const finished = ["paired", "filled", "denied", "timeout", "cancelled", "error", "devices"].includes(this.view.screen);
    if (finished && this.native === null && this.popups.size === 0) this.view = { screen: "idle" };
    this.popups.add(port);
    port.onDisconnect.addListener(() => this.popups.delete(port));
    port.onMessage.addListener((command) => void this.command(port, command));
    port.postMessage(this.view);
  }

  private show(view: View) {
    this.view = view;
    for (const p of this.popups) p.postMessage(view);
  }

  async command(port: Port<PopupCommand, View | { copied: string }>, command: PopupCommand): Promise<void> {
    switch (command.cmd) {
      case "view":
        if (this.view.screen === "idle") await this.status();
        port.postMessage(this.view);
        return;
      case "fill":
        return this.fill(command.tabId);
      case "pair":
        return this.pair();
      case "devices":
        return this.oneShot({ type: "devices" });
      case "forget":
        return this.oneShot({ type: "forget", id: command.id });
      case "cancel":
        this.cancel();
        return;
      case "copy": {
        const held = this.held;
        if (!held) return;
        port.postMessage({ copied: command.what === "username" ? held.username : held.password });
        if (command.what === "password") this.drop();
        return;
      }
      case "reset":
        this.cancel();
        this.drop();
        this.show({ screen: "idle" });
        await this.status();
        return;
    }
  }

  private async status(): Promise<void> {
    let status: HostStatus;
    try {
      const m = await this.api.runtime.sendNativeMessage(HOST, { type: "status" });
      status = m.type === "status" ? { reachable: true, paired: m.paired, phones: m.phones } : { reachable: true, paired: false, phones: 0 };
    } catch (e) {
      status = { reachable: false, paired: false, phones: 0, error: String((e as Error)?.message ?? e) };
    }
    if (this.view.screen === "idle") this.show({ screen: "idle", status });
    // Settings only (README §7): whether a phone is paired, for nothing more than the first frame.
    await this.api.storage.local.set({ v: 1, paired: status.paired }).catch(() => undefined);
  }

  private async oneShot(message: { type: string; id?: string }): Promise<void> {
    try {
      const m = await this.api.runtime.sendNativeMessage(HOST, message);
      if (m.type === "devices") this.show({ screen: "devices", phones: m.phones, key: m.key });
      else if (m.type === "error") this.show({ screen: "error", code: m.code, message: m.message, firefox: this.firefox });
    } catch (e) {
      this.show({ screen: "error", code: "host", message: String((e as Error)?.message ?? e), firefox: this.firefox });
    }
  }

  private open(onMessage: (m: HostMessage) => void): Port<HostMessage, unknown> {
    // One operation at a time: a new one closes the port of the old (its job is replaced by the caller).
    this.close();
    const port = this.api.runtime.connectNative(HOST);
    this.native = port;
    port.onMessage.addListener((m) => {
      if (this.native === port) onMessage(m);
    });
    port.onDisconnect.addListener((p) => {
      if (this.native !== port) return;
      this.native = null;
      const busy = ["starting", "searching", "found", "shown", "pairing"].includes(this.view.screen);
      if (busy) {
        const message = p?.error?.message ?? this.api.runtime.lastError?.message ?? undefined;
        this.show({ screen: "error", code: "host", message, firefox: this.firefox });
      }
    });
    return port;
  }

  async fill(tabId: number): Promise<void> {
    const tab = await this.api.tabs.get(tabId).catch(() => ({ url: undefined as string | undefined }));
    const origin = originOf(tab.url);
    if (!origin) return this.show({ screen: "error", code: "origin", firefox: this.firefox });
    this.drop();
    const site = new URL(origin).host;
    this.job = { tabId, origin, site };
    this.show({ screen: "starting", site, firefox: this.firefox });
    const port = this.open((m) => void this.onFill(m));
    port.postMessage({ type: "fill", origin, browser: browserLabel(this.env.navigator) });
  }

  private async onFill(m: HostMessage): Promise<void> {
    const job = this.job;
    if (!job) return;
    switch (m.type) {
      case "state":
        return this.show({ screen: m.state, site: job.site, since: this.env.now() });
      case "fill": {
        this.close();
        await this.place(job, m.username, m.password, m.phone);
        return;
      }
      case "done":
        this.close();
        return this.show({ screen: m.decision === "deny" ? "denied" : m.decision === "cancelled" ? "cancelled" : "timeout", site: job.site });
      case "error":
        this.close();
        return this.show({ screen: "error", code: m.code, message: m.message, firefox: this.firefox });
      default:
        return;
    }
  }

  /** Into the tab the request came from, if it is still on that site (D549); else "Copy". */
  private async place(job: { tabId: number; origin: string; site: string }, username: string, password: string, phone?: string) {
    const now = await this.api.tabs.get(job.tabId).catch(() => ({ url: undefined as string | undefined }));
    if (originOf(now.url) !== job.origin) return this.hold(job.site, username, password, "tab_changed");
    let filled = false;
    try {
      const results = await this.api.scripting.executeScript({
        target: { tabId: job.tabId, allFrames: true },
        func: fillCredentials,
        args: [username, password, job.origin],
      });
      filled = results.some((r) => r.result?.result === "filled");
    } catch {
      filled = false;
    }
    if (filled) return this.show({ screen: "filled", site: job.site, username, phone });
    this.hold(job.site, username, password, "no_field");
  }

  private hold(site: string, username: string, password: string, why: "no_field" | "tab_changed") {
    this.drop();
    const timer = this.env.setTimeout(() => {
      this.drop();
      if (this.view.screen === "copy") this.show({ screen: "idle" });
    }, COPY_MILLIS);
    this.held = { username, password, timer };
    this.show({ screen: "copy", site, username, why, until: this.env.now() + COPY_MILLIS });
  }

  /** Forgets the held login. A JavaScript string cannot be zeroed; dropping the last reference is what there is. */
  private drop() {
    if (this.held) this.env.clearTimeout(this.held.timer);
    this.held = null;
  }

  async pair(): Promise<void> {
    this.drop();
    this.show({ screen: "starting", site: "", firefox: this.firefox });
    const port = this.open((m) => {
      switch (m.type) {
        case "pair":
          return this.show({ screen: "pairing", svg: m.svg, fingerprint: m.fingerprint, computer: m.computer, expires: m.expires });
        case "paired":
          this.close();
          return this.show({ screen: "paired", phone: m.phone });
        case "error":
          this.close();
          return this.show({ screen: "error", code: m.code, message: m.message, firefox: this.firefox });
        case "done":
          this.close();
          return this.show({ screen: "cancelled" });
        default:
          return;
      }
    });
    port.postMessage({ type: "pair" });
  }

  private close() {
    const port = this.native;
    this.native = null;
    try {
      port?.disconnect();
    } catch {
      // Already gone.
    }
  }

  /** Closing the port is the cancel: the host notices and stops talking to the phone. */
  cancel() {
    const busy = this.native !== null;
    this.close();
    this.job = null;
    if (busy) this.show({ screen: "cancelled" });
  }

  /** For the tests: whether a login is held. */
  get holding(): boolean {
    return this.held !== null;
  }
}
