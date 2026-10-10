---
title: FAQ
description: Questions about Unitool — privacy, 2FA, platforms and development.
class: faq
---

# Frequently asked questions

Not here? Write to [{{contact}}](mailto:{{contact}}).

## General

<details>
<summary>What is Unitool?</summary>

One app with several small tools — a calculator, a currency converter and a 2FA code generator
today. Home shows the ones you chose; the rest are under **+**. See the
[introduction](/docs/).

</details>

<details>
<summary>Is it free?</summary>

Yes. Everything in Unitool today is free, and there are no ads.

</details>

<details>
<summary>Do I need an account?</summary>

No. There is no sign-in of any kind, and nothing to register before the app works.

</details>

<details>
<summary>Which languages does it speak?</summary>

English and Russian. Switch in **Settings → Language**; it changes at once, without a restart.

</details>

<details>
<summary>Some tools are not in the catalogue. Why?</summary>

Some tools, and some ways of starting one, open only with a code from an organisation or from the
tool's author. See [Promo codes and QR activation](/docs/activation/).

</details>

## Privacy and security

<details>
<summary>What does the app send to the internet?</summary>

Three things, each only when you use the tool that needs it: a request for the exchange-rate table,
the GitHub username you type for a 2FA export, and a notification registration once you use a tool
that sends notifications. Nothing you type into the calculator or the converter, and no 2FA key in
readable form, ever leaves the phone. Details: [How your data is kept](/docs/security/#what-leaves-the-phone).

</details>

<details>
<summary>Is there analytics or a crash reporter?</summary>

No. There is no analytics SDK, no crash reporter and no advertising ID in the app.

</details>

<details>
<summary>Why does a calculator have the internet permission?</summary>

For the converter's exchange rates, for notifications, and for the GitHub key lookup in the 2FA
export. The [privacy policy](/privacy/#permissions) lists every permission, including the ones
libraries add.

</details>

<details>
<summary>Can you see my 2FA codes?</summary>

No. Codes are computed on the phone from keys that never leave it in readable form. An export is
encrypted on the phone to SSH keys you choose, and goes only where you send it.

</details>

## 2FA codes

<details>
<summary>Can I move my accounts from Google Authenticator?</summary>

Yes. Export them in Google Authenticator as QR codes and scan them with **Add account → Scan QR
code**. See [Importing from Google Authenticator](/docs/two-factor/#importing-from-google-authenticator).

</details>

<details>
<summary>What happens if I lose my phone?</summary>

The accounts on it cannot be read without unlocking it, and they cannot be copied off it. To get
them back on a new phone you need an [export](/docs/two-factor/#exporting-your-accounts) made
beforehand, or each site's backup codes. Make an export when you add an account you cannot afford
to lose.

</details>

<details>
<summary>Why GitHub SSH keys for the export?</summary>

Because most people who use 2FA seriously already have one, its public half is published at
`github.com/<username>.keys`, and the private half is already protected on their computer. The
phone needs only the public key to encrypt; nobody but the holder of the private key can decrypt.
The file is in the open [age](https://age-encryption.org) format.

</details>

<details>
<summary>I have no SSH key. What now?</summary>

Make one on your computer with `ssh-keygen -t ed25519` and add the `.pub` file to your GitHub
account under **Settings → SSH and GPG keys**. Then export to your username.

</details>

<details>
<summary>Are counter-based (HOTP) codes supported?</summary>

Not yet. Time-based codes (TOTP) with SHA1, SHA256 or SHA512, 6 to 8 digits and any period from 1
to 300 seconds are.

</details>

<details>
<summary>My phone has no screen lock. Is the 2FA app protected?</summary>

No — there is nothing to check you against. The app says so above its screen and offers to set up a
screen lock. Set one up.

</details>

## Platforms

<details>
<summary>Where can I get it?</summary>

Android in Google Play testing, iPhone and iPad in TestFlight. Desktop, web and the command line run
from source. See [Install](/docs/install/).

</details>

<details>
<summary>Does it work offline?</summary>

Everything except fetching new exchange rates. The converter keeps the last table it downloaded.

</details>

<details>
<summary>Is there a desktop or browser version?</summary>

The same code runs on the desktop and in the browser, but there are no packaged releases yet; see
[Build from source](/docs/build-from-source/). Camera scanning and the secure-hardware vault are on
phones only.

</details>

## Development

<details>
<summary>Is Unitool open source?</summary>

The framework it is built on, [Superizer]({{superizerUrl}}), is open source under the MIT licence.
The Unitool repository itself is not public yet.

</details>

<details>
<summary>How do I add a tool of my own?</summary>

A tool is a Kotlin module on Superizer's app contract: a manifest, a config and a screen. See
[Adding an app](/docs/architecture/#adding-an-app).

</details>
