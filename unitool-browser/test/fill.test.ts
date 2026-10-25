// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { fillCredentials } from "../src/fill";

function page(html: string) {
  document.body.innerHTML = html;
}
const value = (sel: string) => (document.querySelector(sel) as HTMLInputElement).value;

describe("fillCredentials (D549)", () => {
  const origin = location.origin;

  it("fills autocomplete=username and the password, and submits nothing", () => {
    page(`<form id=f><input id=other type=text><input id=u autocomplete="username"><input id=p type=password></form>`);
    let submitted = false;
    document.getElementById("f")!.addEventListener("submit", () => (submitted = true));
    expect(fillCredentials("octocat", "pw", origin)).toEqual({ result: "filled", login: true });
    expect(value("#u")).toBe("octocat");
    expect(value("#p")).toBe("pw");
    expect(value("#other")).toBe("");
    expect(submitted).toBe(false);
  });

  it("without autocomplete takes the nearest text field before the password", () => {
    page(`<input id=search type=text><div><input id=email type=email></div><input id=p type=password><input id=after type=text>`);
    fillCredentials("me@x", "pw", origin);
    expect(value("#email")).toBe("me@x");
    expect(value("#search")).toBe("");
    expect(value("#after")).toBe("");
  });

  it("skips hidden and disabled fields and prefers current-password", () => {
    page(`<input id=h type=password style="display:none"><input id=new type=password autocomplete="new-password"><input id=cur type=password autocomplete="current-password"><input id=d type=password disabled>`);
    fillCredentials("", "pw", origin);
    expect(value("#cur")).toBe("pw");
    expect(value("#new")).toBe("");
    expect(value("#h")).toBe("");
  });

  it("is seen by a framework listening for input events", () => {
    page(`<input id=u autocomplete=username><input id=p type=password>`);
    const seen: string[] = [];
    for (const id of ["u", "p"]) document.getElementById(id)!.addEventListener("input", (e) => seen.push((e.target as HTMLInputElement).value));
    // React keeps its own copy of the value via the instance setter; the prototype setter goes past it.
    const p = document.getElementById("p") as HTMLInputElement;
    const proto = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!;
    Object.defineProperty(p, "value", { set() { throw new Error("instance setter used"); }, get() { return proto.get!.call(this); }, configurable: true });
    expect(fillCredentials("a", "b", origin).result).toBe("filled");
    expect(seen).toEqual(["a", "b"]);
  });

  it("refuses another origin and reports a page with no password field", () => {
    page(`<input id=p type=password>`);
    expect(fillCredentials("a", "b", "https://evil.example")).toEqual({ result: "wrong_origin" });
    expect(value("#p")).toBe("");
    page(`<input type=text>`);
    expect(fillCredentials("a", "b", origin)).toEqual({ result: "not_found" });
  });

  it("carries no imports: executeScript sends the function's source alone", () => {
    const source = fillCredentials.toString();
    expect(source).not.toMatch(/\b(import|require)\b/);
    // It runs again from its own text, as the browser will run it.
    const again = new Function(`return (${source})`)() as typeof fillCredentials;
    page(`<input id=p type=password>`);
    expect(again("", "x", origin).result).toBe("filled");
  });
});
