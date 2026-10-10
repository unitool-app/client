---
title: Architecture
description: How Unitool is put together — a host on Superizer, apps as independent modules, a shared kit.
---

# Architecture

## A host and its apps

Unitool is a **host**. It provides the home screen, the menu, the app catalogue, Settings,
activation by code or QR, deep links and notifications. The tools — the calculator, the converter,
the 2FA app — are **apps**: independent modules that know nothing about Unitool and would run under
any other host.

The machinery for that is [Superizer]({{superizerUrl}}), an open-source (MIT) Kotlin Multiplatform
framework developed alongside Unitool. Everything hosts have in common lives there: the drawer,
All apps, Settings, the activation screens, the design tokens, storage, networking, push routing
and the app lock. What is left in Unitool itself is short: a name, a list of `register()` calls and
a table of activation codes.

```
composeApp/        the host — and nothing else
apps/calculator/   an app: manifest, config, instance, screen, its own strings
apps/currency-converter/
apps/twofa/
services/          optional services apps ask for by key: qr-scanner, share, vault, …
unitool-kit/       code the phone and the command line share; no dependencies
unitool-cli/       the unitool command, same repository
```

## Rules the code keeps

- **An app depends on Superizer's contract, never on the host or another app.** A Gradle task,
  `checkDependencyRules`, fails the build otherwise.
- **The command line may not depend on an app, a service, Superizer or Compose.** Code both sides
  need moves to `unitool-kit`, which depends on nothing.
- **Logic is pure and apart from the UI.** The calculator is
  `CalculatorState.press(Key): CalculatorState`; the converter is `Rates.convert(amount, from, to)`.
  Both are tested without a screen and without a network.
- **Strings are interfaces, and each app owns its own.** A language missing a line does not compile.
- **No Material theme.** A white surface, near-black text, one black accent, colour only for state.
- **Navigation is a sealed hierarchy**, with no navigation library.
- **Cryptography is written in common code and tested against standard vectors** — SHA-1/256/512,
  HMAC, X25519, ChaCha20-Poly1305 and RSA-OAEP for age — because the web target has no JCA. The
  tests also check the results against the JDK and the real `age` tool.

## Adding an app

1. `include(":apps:weather")` in `settings.gradle.kts`.
2. A `build.gradle.kts` copied from `apps/calculator` — Superizer by Maven coordinates, never
   `superizer:host` and never another app.
3. `WeatherApp : SuperizerApp<WeatherConfig>()` — a manifest, a config spec and an instance.
4. `implementation(project(":apps:weather"))` in `composeApp/build.gradle.kts`.
5. `register(WeatherApp())` in `UnitoolHost.kt`.

Nothing in Superizer changes. A test, `ExtensibilityTest`, holds that claim: it registers an app
that exists only in the test sources and drives it from the home screen.

## Activation and links

An app declares a config — the calculator's is one field, `mode`. A link
`unitool://app/<id>/<path>?…` or an activation payload opens an app with a config, so a code can
hand out "the calculator in scientific mode" without a setting for it. Activation codes are kept in
the build only as hashes.
