import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { beforeAll, describe, expect, it } from "vitest";

const read = (target: string) => JSON.parse(readFileSync(`dist/${target}/manifest.json`, "utf8"));

beforeAll(() => {
  execFileSync("node", ["build.mjs"], { stdio: "ignore" });
});

describe("the built manifests (D549, D554)", () => {
  for (const target of ["chrome", "firefox"]) {
    it(`${target}: exactly the four permissions, no host access, no content scripts`, () => {
      const m = read(target);
      expect(m.name).toBe("Unitool");
      expect(m.short_name).toBe("Unitool");
      expect(m.manifest_version).toBe(3);
      expect(m.permissions).toEqual(["nativeMessaging", "activeTab", "scripting", "storage"]);
      expect(m.host_permissions).toBeUndefined();
      expect(m.optional_permissions).toBeUndefined();
      expect(m.content_scripts).toBeUndefined();
      expect(m.externally_connectable).toBeUndefined();
      expect(m.web_accessible_resources).toBeUndefined();
      expect(m.commands._execute_action.suggested_key.default).toBe("Ctrl+Shift+L");
      expect(m.content_security_policy.extension_pages).toBe("script-src 'self'; object-src 'none'");
    });
  }

  it("chrome: a service worker and the key that fixes the id the host allows (D567)", async () => {
    const m = read("chrome");
    expect(m.background.service_worker).toBe("background.js");
    const der = Buffer.from(m.key, "base64");
    const { createHash } = await import("node:crypto");
    const id = createHash("sha256").update(der).digest("hex").slice(0, 32).replace(/[0-9a-f]/g, (c) => "abcdefghijklmnop"[parseInt(c, 16)]!);
    expect(id).toBe("omcnlonedghinfecceojlapofcjfcpoi");
  });

  it("firefox: background scripts and the add-on id the host allows", () => {
    const m = read("firefox");
    expect(m.background.scripts).toEqual(["background.js"]);
    expect(m.browser_specific_settings.gecko.id).toBe("browser@unitool.m42.cx");
  });

  it("every message exists in both languages", () => {
    const ru = JSON.parse(readFileSync("_locales/ru/messages.json", "utf8"));
    const en = JSON.parse(readFileSync("_locales/en/messages.json", "utf8"));
    expect(Object.keys(ru).sort()).toEqual(Object.keys(en).sort());
  });

  it("the built scripts log nothing", () => {
    for (const file of ["dist/chrome/background.js", "dist/chrome/popup/popup.js"]) {
      expect(readFileSync(file, "utf8")).not.toMatch(/console\.(log|info|debug|warn|error)/);
    }
  });
});
