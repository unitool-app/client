---
title: The cards
description: What each card of Test App shows and what its buttons do.
---

# The cards

The cards appear in this order. Values are printed as `name = value`, in a monospaced font.

## Identity

Who is running the app: `appId`, the host's name, version and build, the platform, the contract
version the host implements, and whether this is a debug build.

## Config

The configuration this visit was opened with, as JSON — `mode` and `label`. `mode` is `promo` when
the code opened it, `deeplink` when the [echo link](/docs/test-app/#opening-it-by-link) did, and
`default` otherwise. Nothing else says where it came from: there is nothing for the app to read.

## Lifecycle

The current lifecycle state, how many times the app came to the foreground and went to the
background, and the host's events — those about this app and those about no app in particular —
newest first. **Close me** asks the host to close
the app.

## State

A text field. *This text survives backgrounding and a process death.* Type something, leave the
app in the background until the system ends it, and come back.

## Storage

`launches` — how many times the app has been opened on this device. The number is kept in the
app's storage, so it survives everything except **Clear**, which resets it.

## Network

`online` — whether the device has a connection. **Ping** fetches one exchange rate from
`api.frankfurter.app` and shows the HTTP status and the time it took, or why it failed.

## Analytics

**Send event** records an event named `test`. *The host prefixes the app id before this leaves the
process.* In Unitool it goes only to the app's own log on the device; nothing is sent anywhere.

## Navigation

- **Open Calculator (scientific)** — opens another tool with a configuration.
- **Open unknown** — asks for a tool that does not exist; the card shows the error it gets.
- **Open Settings** — opens Settings.

## Services

The optional services the host offers this app, by name, and the answer to a request for one
nobody provides (`camera`): `null`. That `null` is what a tool gets for anything the platform does
not have.

## Secrets

**Store a secret** saves a value through the host's secret storage, which seals it — by the chip on
a phone, by an SSH key on the desktop and the web. The card shows the names of the stored secrets,
never their values, and the storage findings this app can see, as `code:status`.

## SSH keyring

Whether the SSH keyring is there, and the keys this app has permission to use. **Sign with a key**
signs a short test message in the namespace `test-app@superizer`:

- with no permission yet, the host asks *Which key may “test-app” sign with?* — tapping a key gives
  the permission;
- then the key's own confirmation, as for [any use of a key](/docs/ssh-keys/use/#confirming-a-use).

The card shows the key's type and fingerprint and the first lines of the signature, or what stopped
it. A signature in this namespace is useless for anything but this test. The key's
**Recent use** records each attempt.

## Locale

The language tag in use, and a line from the app's own string table — it changes with
**Settings → Language**.

## Haptics

**Tick** — a short vibration, where the device has one.

## Push

`enabled`, the topics the app is subscribed to, and the last notification it received — its topic,
data and link. A text field holds a topic name, `demo` by default:

- **Subscribe** and **Unsubscribe** — for that topic. Only `demo` is declared by the app; any other
  topic is refused and logged.
- **Request permission** — asks the system for the notification permission.

## Auth

The current sign-in session. Unitool has no accounts, so it reads `null (anonymous)`.

## Veto

**Block close** — with it on, Back shows the host's dialog instead of closing the app. *With this
on, back asks the host's dialog instead of closing.*
