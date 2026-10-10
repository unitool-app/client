---
title: Signing in to this website
description: How a browser signs in with the Unitool app, what it is then shown, and what the server keeps.
---

# Signing in to this website

Some parts of Unitool are not in the catalogue: they open only with a code (see
[Promo codes and QR activation](/docs/activation/)). Their documentation is not public either. A
browser signed in with your app sees the docs of the parts **your app has opened** — and nothing
about the ones it has not.

## How to sign in

1. On the computer, open [Sign in](/login/). The page shows a QR code.
2. In the app, open **Settings → Website** and tap **Scan QR code to sign in**. Without a camera,
   tap **Paste a sign-in link** and paste the link shown under *No camera on the phone?*.
3. The phone shows which browser asked and from which address. Tap **Sign in** only if it is the
   browser in front of you.

The page on the computer notices by itself and opens [Account](/account/). The docs sidebar now has
a group for each hidden part your app has opened. Open another part on the phone, and its pages
appear at the next page you open.

> **Never scan a sign-in code someone else shows you.** Whoever showed it would be signed in as your
> app. That is why the phone shows the browser and waits for your tap.

## What the browser is signed in as

Your **installation of Unitool** — not you. There is no name, no email, no password and no account
anywhere in it. The first sign-in registers the installation with our server: a random id, a hash of
a secret kept on the phone, the platform, the app version and the list of hidden parts it has opened.

## What the server keeps

| What | How long |
|---|---|
| The installation's registration, with the list of hidden parts it has opened | while the installation exists; write to [{{contact}}](mailto:{{contact}}) to have it deleted |
| Each signed-in browser: its user-agent string, the address it signed in from, when | until it signs out, or 30 days after its last visit |
| Which page a signed-in browser opened, and when | 90 days |

Nothing is recorded about a browser that is not signed in.

## Signing out

- In the browser: [Account](/account/) → **Sign out**.
- On the phone: **Settings → Website → Signed-in browsers** — sign one browser out, or all of them.

Removing the app does not reach the server. Sign your browsers out first; otherwise they stay signed
in until 30 days after their last visit.
