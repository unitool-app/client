import type { PopupCommand, View } from "../protocol";

/**
 * The popup: draws the background's [View] and sends its clicks back (README §7). It never holds a
 * password, except for the moment between a "Copy" click and the clipboard write.
 */
type Chrome = {
  runtime: { connect(info: { name: string }): PopupPort };
  tabs: { query(q: { active: boolean; currentWindow: boolean }): Promise<{ id?: number; url?: string }[]> };
  i18n: { getMessage(key: string, substitutions?: string | string[]): string };
};
type PopupPort = {
  postMessage(m: PopupCommand): void;
  onMessage: { addListener(fn: (m: View | { copied: string }) => void): void };
};

const api = ((globalThis as { browser?: unknown }).browser ?? (globalThis as { chrome?: unknown }).chrome) as Chrome;
const t = (key: string, sub?: string) => api.i18n.getMessage(key, sub) || key;
const app = document.getElementById("app")!;
const port = api.runtime.connect({ name: "popup" });
let started = false;
let tick: ReturnType<typeof setInterval> | undefined;
let clipboardClear: ReturnType<typeof setTimeout> | undefined;
let copiedPassword: string | null = null;

function el<K extends keyof HTMLElementTagNameMap>(tag: K, text?: string, cls?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (text !== undefined) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}

function button(text: string, onClick: () => void, cls = "", id = ""): HTMLButtonElement {
  const b = el("button", text, cls);
  if (id) b.id = id;
  b.addEventListener("click", onClick);
  return b;
}

function send(command: PopupCommand) {
  port.postMessage(command);
}

async function activeTab(): Promise<{ id?: number; url?: string } | undefined> {
  const [tab] = await api.tabs.query({ active: true, currentWindow: true });
  return tab;
}

function countdown(target: HTMLElement, until: number) {
  const draw = () => {
    target.textContent = t("secondsLeft", String(Math.max(0, Math.round((until - Date.now()) / 1000))));
  };
  draw();
  tick = setInterval(draw, 1000);
}

function errorText(code: string, message: string | undefined, firefox: boolean): string {
  const key = code === "host" ? (firefox ? "err_host_firefox" : "err_host") : `err_${code.replace(/-/g, "_")}`;
  const text = api.i18n.getMessage(key);
  return text || t("err_other", message ?? code);
}

function render(view: View) {
  if (tick) clearInterval(tick);
  app.replaceChildren();
  app.dataset.screen = view.screen;
  switch (view.screen) {
    case "idle": {
      const status = view.status;
      if (!status) {
        app.append(el("p", t("starting"), "muted"));
        return;
      }
      if (!status.reachable) {
        app.append(el("p", errorText("host", status.error, /Firefox\//.test(navigator.userAgent)), "danger"));
        return;
      }
      if (!status.paired) {
        app.append(el("p", t("notPaired")), button(t("pairPhone"), () => send({ cmd: "pair" }), "", "pair"));
        return;
      }
      if (!started) {
        // Opening the popup is the request (README §7): the click or Ctrl+Shift+L gave this tab.
        started = true;
        void activeTab().then((tab) => {
          if (tab?.id !== undefined && /^https?:/.test(tab.url ?? "")) send({ cmd: "fill", tabId: tab.id });
          else render({ screen: "error", code: "origin", firefox: false });
        });
      }
      app.append(button(t("request"), () => void activeTab().then((tab) => tab?.id !== undefined && send({ cmd: "fill", tabId: tab.id })), "", "request"));
      app.append(button(t("phones"), () => send({ cmd: "devices" }), "secondary", "phones"));
      return;
    }
    case "starting":
      if (view.site) app.append(el("h1", view.site));
      app.append(el("p", t("starting"), "muted"));
      if (view.firefox) app.append(el("p", t("firefoxAsk")));
      app.append(button(t("cancel"), () => send({ cmd: "cancel" }), "secondary", "cancel"));
      return;
    case "searching":
    case "found":
    case "shown": {
      app.append(el("h1", view.site, "site"));
      app.append(el("p", t(view.screen), view.screen === "shown" ? "ok" : ""));
      if (view.screen === "searching") app.append(el("p", t("searchingHint"), "muted"));
      const left = el("p", "", "muted");
      app.append(left);
      countdown(left, view.since + 60_000);
      app.append(button(t("cancel"), () => send({ cmd: "cancel" }), "secondary", "cancel"));
      return;
    }
    case "filled":
      app.append(el("h1", view.site));
      app.append(el("p", view.username ? t("filled", view.username) : t("filledNoLogin"), "ok"));
      app.append(el("p", t("submitYourself"), "muted"));
      return;
    case "copy": {
      app.append(el("h1", view.site));
      app.append(el("p", view.why === "no_field" ? t("noField") : t("tabChanged", view.site)));
      if (view.username) app.append(button(t("copyLogin"), () => send({ cmd: "copy", what: "username" }), "secondary", "copy-login"));
      app.append(button(t("copyPassword"), () => send({ cmd: "copy", what: "password" }), "", "copy-password"));
      app.append(el("p", t("clipboardNote"), "muted"));
      return;
    }
    case "denied":
    case "timeout":
    case "cancelled":
      if (view.site) app.append(el("h1", view.site));
      app.append(el("p", t(view.screen)));
      app.append(button(t("again"), () => send({ cmd: "reset" }), "secondary", "again"));
      return;
    case "error":
      app.append(el("p", errorText(view.code, view.message, view.firefox), "danger"));
      app.append(button(t("back"), () => send({ cmd: "reset" }), "secondary", "back"));
      return;
    case "pairing": {
      app.append(el("p", t("pairTitle")));
      // The host's SVG, as a picture: nothing of it runs here.
      const img = el("img", undefined, "qr");
      img.alt = "QR";
      img.src = "data:image/svg+xml;base64," + btoa(view.svg);
      app.append(img);
      app.append(el("p", t("pairSteps"), "muted"));
      app.append(el("p", t("pairCheck", view.computer), "muted"));
      app.append(el("code", view.fingerprint));
      const left = el("p", "", "muted");
      app.append(left);
      countdown(left, view.expires);
      app.append(button(t("cancel"), () => send({ cmd: "cancel" }), "secondary", "cancel"));
      return;
    }
    case "paired":
      app.append(el("p", t("paired", view.phone), "ok"));
      app.append(button(t("back"), () => send({ cmd: "reset" }), "secondary", "back"));
      return;
    case "devices": {
      const list = el("ul");
      for (const phone of view.phones) {
        const li = el("li");
        li.append(el("span", phone.name), button(t("forget"), () => send({ cmd: "forget", id: phone.id }), "secondary"));
        list.append(li);
      }
      app.append(view.phones.length ? list : el("p", t("noPhones"), "muted"));
      if (view.key) app.append(el("p", t("keyLine", `${view.key.path} (${view.key.fingerprint ?? "?"})`), "muted"));
      app.append(button(t("pairAnother"), () => send({ cmd: "pair" }), "", "pair"));
      app.append(button(t("back"), () => send({ cmd: "reset" }), "secondary", "back"));
      return;
    }
  }
}

port.onMessage.addListener((m) => {
  if ("copied" in m) {
    void navigator.clipboard.writeText(m.copied).then(() => {
      const isPassword = document.activeElement?.id === "copy-password";
      if (isPassword) {
        copiedPassword = m.copied;
        if (clipboardClear) clearTimeout(clipboardClear);
        // Only while this window lives (README §7); the note on the screen says so.
        clipboardClear = setTimeout(() => {
          void navigator.clipboard.readText().then((now) => {
            if (now === copiedPassword) void navigator.clipboard.writeText("");
            copiedPassword = null;
          }).catch(() => void navigator.clipboard.writeText(""));
        }, 60_000);
      }
      const done = el("p", t("copied"), "ok");
      app.append(done);
    });
    return;
  }
  render(m);
});
send({ cmd: "view" });
