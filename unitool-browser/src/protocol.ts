/**
 * The words between the parts of the extension, and with the host (passwords-browser 01 §5, D560).
 * No password ever crosses to the popup except as the answer to its own "copy" click.
 */

export const HOST = "cx.m42.unitool";

/** What `unitool browser host` sends. */
export type HostMessage =
  | { type: "status"; paired: boolean; phones: number; key?: string }
  | { type: "state"; state: "searching" | "found" | "shown" }
  | { type: "fill"; username: string; password: string; phone?: string }
  | { type: "done"; decision: "deny" | "timeout" | "cancelled" | string }
  | { type: "error"; code: string; message?: string }
  | { type: "pair"; uri: string; svg: string; fingerprint: string; computer: string; expires: number }
  | { type: "paired"; phone: string }
  | { type: "devices"; phones: { id: string; name: string; pairedAt: number }[]; key?: { path: string; fingerprint?: string } };

/** The popup asks the background. */
export type PopupCommand =
  | { cmd: "view" }
  | { cmd: "fill"; tabId: number }
  | { cmd: "pair" }
  | { cmd: "devices" }
  | { cmd: "forget"; id: string }
  | { cmd: "cancel" }
  | { cmd: "copy"; what: "username" | "password" }
  | { cmd: "reset" };

/** What the popup draws: the background's state, never a password. */
export type View =
  | { screen: "idle"; status?: HostStatus; site?: string }
  | { screen: "starting"; site: string; firefox: boolean }
  | { screen: "searching" | "found" | "shown"; site: string; since: number }
  | { screen: "filled"; site: string; username: string; phone?: string }
  | { screen: "copy"; site: string; username: string; why: "no_field" | "tab_changed"; until: number }
  | { screen: "denied" | "timeout" | "cancelled"; site?: string }
  | { screen: "error"; code: string; message?: string; firefox: boolean }
  | { screen: "pairing"; svg: string; fingerprint: string; computer: string; expires: number }
  | { screen: "paired"; phone: string }
  | { screen: "devices"; phones: { id: string; name: string; pairedAt: number }[]; key?: { path: string; fingerprint?: string } };

export type HostStatus = { reachable: boolean; paired: boolean; phones: number; error?: string };

/** "Chrome 141 · Linux": what the phone's sheet shows as the computer's word (README §5). */
export function browserLabel(nav: { userAgent: string; userAgentData?: { brands?: { brand: string; version: string }[]; platform?: string } }): string {
  const data = nav.userAgentData;
  const ua = nav.userAgent;
  let name = "Browser";
  const firefox = /Firefox\/(\d+)/.exec(ua);
  if (firefox) {
    name = `Firefox ${firefox[1]}`;
  } else if (data?.brands) {
    const known = ["Google Chrome", "Microsoft Edge", "Brave", "Opera", "Vivaldi", "Chromium"];
    const brand = known.map((k) => data.brands!.find((b) => b.brand === k)).find(Boolean);
    if (brand) name = `${brand.brand === "Google Chrome" ? "Chrome" : brand.brand.replace("Microsoft ", "")} ${brand.version}`;
  } else {
    const chrome = /(Edg|Chrome|Chromium)\/(\d+)/.exec(ua);
    if (chrome) name = `${chrome[1] === "Edg" ? "Edge" : chrome[1]} ${chrome[2]}`;
  }
  const platform = data?.platform || (/Linux/.test(ua) ? "Linux" : /Mac OS X/.test(ua) ? "macOS" : /Windows/.test(ua) ? "Windows" : "");
  return platform ? `${name} · ${platform}` : name;
}

/** `https://github.com/login?x` → `https://github.com`; null for anything that is not a web page. */
export function originOf(url: string | undefined): string | null {
  if (!url) return null;
  try {
    const u = new URL(url);
    return u.protocol === "https:" || u.protocol === "http:" ? u.origin : null;
  } catch {
    return null;
  }
}
