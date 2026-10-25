const api = globalThis.browser ?? chrome;
const H = "cx.m42.unitool.probe";
(async () => {
  try {
    console.log("PROBE-START");
    const r1 = await api.runtime.sendNativeMessage(H, { step: 1 });
    await api.runtime.sendNativeMessage(H, { step: 2, got: r1 });
    const port = api.runtime.connectNative(H);
    let once = false; port.onMessage.addListener((m) => { if (once) return; once = true; port.postMessage({ step: 4, got: m }); setTimeout(() => port.disconnect(), 500); });
    port.postMessage({ step: 3 });
  } catch (e) {
    console.log("PROBE-ERROR " + String(e));
    try { await api.runtime.sendNativeMessage(H, { error: String(e) }); } catch (_) {}
  }
})();
