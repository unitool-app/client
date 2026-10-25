import { Background, type Api } from "./core";

/** The real browser: `browser.*` in Firefox, `chrome.*` in Chromium — both promise-based in MV3. */
const api = ((globalThis as { browser?: unknown }).browser ?? (globalThis as { chrome?: unknown }).chrome) as Api;

new Background(api, {
  now: () => Date.now(),
  setTimeout: (fn, ms) => setTimeout(fn, ms),
  clearTimeout: (id) => clearTimeout(id as ReturnType<typeof setTimeout>),
  navigator,
});
