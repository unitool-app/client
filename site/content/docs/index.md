---
title: Introduction
description: What Unitool is and how this documentation is organised.
---

# Introduction

Unitool is one app that holds several small tools: a calculator, a currency converter and a
two-factor (2FA) code generator today. It has no ads, no account and no tracking, and it opens
straight onto the tool you use most.

Under the hood Unitool is a *host*. Each tool is an independent module that the host registers,
shows on the home screen and opens when you tap it. The host itself is built on
[Superizer]({{superizerUrl}}), an open framework for this kind of app.

## How these docs are organised

| Section | For |
|---|---|
| **Getting started** | installing the app, finding your way around Home, and activating tools with a code or a QR code |
| **Apps** | each tool, one page each |
| **Command line** | `unitool`, the terminal companion for Linux |
| **Security and privacy** | where your data is kept, what the app lock does, and what leaves the phone |
| **Developers** | building from source and how the pieces fit together |

If you have a question rather than a task, the [FAQ](/faq/) may answer it faster.

## Platforms

| Platform | Status |
|---|---|
| Android 7.0 and later | testing on Google Play |
| iPhone and iPad | testing on TestFlight |
| Desktop (Linux, macOS, Windows) | development builds from source |
| Web (WebAssembly) | development builds from source |
| `unitool` command line (Linux x86-64) | development builds from source |

The same code draws the same screens on every platform. A few features need hardware a platform
does not have — camera scanning, for example, is on phones only — and those pages say so.
