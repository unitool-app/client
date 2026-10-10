---
title: Test App
description: The hidden bench app that shows every service Unitool gives a tool, one card each.
---

# Test App

Test App is a tool for people building on Unitool or checking how it behaves. It is one scrolling
page with one card per service the app framework gives a tool: identity, configuration, lifecycle,
storage, network, navigation, secrets, SSH keys, notifications and more. Each card shows the
service's current state and has a button or two to exercise it. It does nothing useful on its own.

[The cards](/docs/test-app/cards/) describes each one.

## Unlocking it

Test App is not in **All apps** on a fresh install. The code you were given unlocks it:

1. Open the menu and tap **Premium**.
2. Tap **Have a promo code?**, type the code and tap **Apply**.

Test App opens and is put on Home. From then on it is an ordinary tool on this device: it stays on
Home, a long press takes it off, and **All apps** lists it so a tap puts it back. The code is not needed
again.

## Opening it by link

Once it is unlocked, a link opens it with a label of your choice:

```text
unitool://app/test-app/echo?x=hello
```

The **Config** card then shows `"mode": "deeplink"` and `"label": "hello"`. The same link arriving
from a notification tap gives the same screen: the app cannot tell how it was opened. Before it is
unlocked, the link is treated as a link to an unknown tool.

## Notifications

Test App is the tool in Unitool that takes notifications. The first time it opens, Unitool starts
notifications as described in [Notifications](/docs/security/#notifications), and the system asks
for the permission. Refuse it and everything else still works. Taking Test App off Home stops its
topic subscriptions.

## A configuration it cannot read

Other tools open with their defaults when the configuration they are given is broken. Test App
refuses instead, so the host's error screen can be seen and checked. A QR code or link with a
malformed configuration for it ends on that screen, not on the app.
