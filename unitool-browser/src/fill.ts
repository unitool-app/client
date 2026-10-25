/**
 * Runs inside the page, in every frame `executeScript` reaches (README §7, D549). One function with
 * no imports and no closure: `scripting.executeScript` sends its source, not the module.
 *
 * - only on [expectedOrigin]: a frame of another site is left alone;
 * - the password field is a visible `input[type=password]`; the login is `autocomplete=username`
 *   or `email`, else the nearest text field before the password;
 * - values go through the native setter plus `input` and `change`, so React and Vue see them;
 * - the form is never submitted.
 */
export type FillResult = { result: "filled"; login: boolean } | { result: "not_found" } | { result: "wrong_origin" };

export function fillCredentials(username: string, password: string, expectedOrigin: string): FillResult {
  if (location.origin !== expectedOrigin) return { result: "wrong_origin" };

  // Layout exists in a browser; in a document without one (tests), the box check is skipped.
  const hasLayout = document.documentElement.getBoundingClientRect().width > 0;
  const usable = (el: HTMLInputElement): boolean => {
    if (el.disabled || el.readOnly || el.type === "hidden" || el.hidden) return false;
    const style = getComputedStyle(el);
    if (style.display === "none" || style.visibility === "hidden" || style.opacity === "0") return false;
    if (hasLayout) {
      const box = el.getBoundingClientRect();
      if (box.width < 1 || box.height < 1) return false;
    }
    return true;
  };
  const inputs = Array.from(document.querySelectorAll("input")).filter(usable);
  const passwords = inputs.filter((el) => el.type === "password");
  if (passwords.length === 0) return { result: "not_found" };
  const target = passwords.find((el) => el.autocomplete === "current-password") ?? passwords[0]!;

  const textual = (el: HTMLInputElement) => ["text", "email", "tel", ""].includes(el.type) || el.type === "username";
  let login = inputs.find((el) => /\b(username|email)\b/.test(el.getAttribute("autocomplete") ?? "") && textual(el));
  if (!login) {
    const before = inputs.filter((el) => textual(el) && (el.compareDocumentPosition(target) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0);
    login = before[before.length - 1];
  }

  const set = (el: HTMLInputElement, value: string) => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
    el.focus();
    setter.call(el, value);
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  };
  if (login && username) set(login, username);
  set(target, password);
  return { result: "filled", login: Boolean(login && username) };
}
