// The login page's only script (.ai-notes/site/02): ask the server for a QR, then wait for the
// phone. The answer that the phone said yes arrives as a cookie on the polling request.
(() => {
  const API = '/api/unitool/v1/web/logins';
  const qr = document.getElementById('qr');
  const status = document.getElementById('status');
  const noCamera = document.getElementById('no-camera');
  const link = document.getElementById('link');
  const asked = new URLSearchParams(location.search).get('next');
  const target = asked && asked.startsWith('/') && !asked.startsWith('//') ? asked : '/account/';
  let timer = 0;

  const say = (text) => { status.textContent = text; };

  function offerNew(text) {
    clearTimeout(timer);
    qr.innerHTML = '';
    noCamera.hidden = true;
    say(text + ' ');
    const again = document.createElement('button');
    again.className = 'button secondary';
    again.type = 'button';
    again.textContent = 'Show a new code';
    again.onclick = start;
    status.append(again);
  }

  async function start() {
    clearTimeout(timer);
    qr.innerHTML = '<p class="muted">Loading…</p>';
    say('');
    let response;
    try {
      response = await fetch(API, { method: 'POST', credentials: 'same-origin' });
    } catch {
      return offerNew('The server cannot be reached.');
    }
    if (!response.ok) {
      return offerNew(response.status === 429 ? 'Too many codes from this address. Try again later.' : 'The code could not be made.');
    }
    const login = await response.json();
    qr.innerHTML = login.qrSvg;
    link.textContent = login.link;
    noCamera.hidden = false;
    qr.querySelector('svg')?.setAttribute('aria-label', 'Sign-in QR code');
    say('Waiting for the phone…');
    poll(login.loginId);
  }

  async function poll(id) {
    let response;
    try {
      response = await fetch(`${API}/${id}`, { credentials: 'same-origin', cache: 'no-store' });
    } catch {
      timer = setTimeout(() => poll(id), 4000);
      return;
    }
    if (!response.ok) return offerNew('This code can no longer be used.');
    const { state } = await response.json();
    if (state === 'approved') {
      say('Signed in.');
      location.href = target;
      return;
    }
    if (state === 'expired') return offerNew('The code has expired.');
    timer = setTimeout(() => poll(id), 2000);
  }

  start();
})();
