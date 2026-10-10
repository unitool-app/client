---
title: The unitool command
description: unitool, the command-line companion — QR codes and split secrets in the terminal.
---

# The unitool command

`unitool` is the terminal side of Unitool, built from the same repository as the phone app. Like
the phone, it is a set of apps; here an app is a word on the command line with commands under it.

Today there are two: [`qr`](#unitool-qr) and [`split`](#unitool-split).

## Install

`unitool` is one native Linux executable (x86-64) of about 1.4 MB. It needs no JVM, starts in about
30 ms and links only glibc 2.19 or newer.

There are no prebuilt downloads yet, and the repository is not public yet. With access to it, build
from the source tree:

```sh
unitool-cli/bin/unitool --help                   # builds itself on first run, and after a change
ln -s "$PWD/unitool-cli/bin/unitool" ~/bin/      # optional: `unitool` from anywhere

./gradlew :unitool-cli:app:install               # or by hand: unitool-cli/app/build/dist/unitool
```

The build needs the same setup as the app (see [Build from source](/docs/build-from-source/)).
The first build downloads Kotlin/Native's toolchain once.

`unitool` with no arguments at a terminal opens a menu: pick an app and a command, answer the
questions, and at the end it prints the same call as one command line.

## unitool qr

QR codes from text — in the terminal, or as PNG or SVG.

```sh
unitool qr 'https://example.com'               # to the terminal, black on white whatever the theme
echo 'from a pipe' | unitool qr                # no TEXT: read stdin, minus the final line break
unitool qr 'hello' -o code.png                 # the format follows the extension: .png, .svg
unitool qr 'hello' -f svg > code.svg           # or is named; PNG is refused on a terminal
unitool qr 'hello' --ec H --scale 12 -o big.png
```

Text longer than one code holds (2953 bytes at `--ec L`) is cut on line breaks with `--split`. At a
terminal the codes come one at a time, with Enter in between; with `-o` they become
`export-1.png`, `export-2.png`, …

```sh
age -d -i ~/.ssh/id_ed25519 2fa-export.age | unitool qr --split
```

That is the way to move a large [2FA export](/docs/two-factor/#moving-to-a-new-phone) to a phone.

## unitool split

A secret in parts. The secret is encrypted into a container file under a random key, and the key
is split so that **any K of N parts** open it and fewer tell nothing at all
([Shamir's secret sharing](https://en.wikipedia.org/wiki/Shamir%27s_secret_sharing)). Give the
parts to different people or keep them in different places.

Each part is a QR code of an ordinary-looking link with the part after `#` — the piece of a URL
that browsers never send to a server.

```sh
unitool split                    # at a terminal: a step-by-step wizard, ending with the command below
printf 'my secret' | unitool split make -n 3 -k 2 --base https://example.com/p -o secret.ucrypt --show png
unitool split join secret.ucrypt -p part-1-of-3.txt -p part-3-of-3.txt
unitool split join secret.ucrypt     # asks for the parts one at a time, hidden as you type
unitool split inspect secret.ucrypt  # what a container or a part is, without opening it
```

- From 2 to 5 parts. The link address is yours to choose; the page there must not run scripts.
- Parts go to the screen one at a time (the screen and its scrollback are cleared after each), or
  to one file per part: `png`, `svg` or `txt`.
- Every file it writes is readable by its owner only (`0600`), and an existing container or part
  is never overwritten — it may be the only copy.
- Secrets never come from the command line arguments: stdin, files, or a question that does not
  echo.

## Conventions

- **stdout carries only the result** — the code, the PNG bytes. Every message goes to stderr, so
  pipes and redirects just work.
- **Exit codes:** `0` success; `1` the command could not do it (text too long, a file that will not
  write); `2` a wrong call (unknown option, bad value); `130` you left the menu with Ctrl-D; `141`
  (silently) the reader of stdout went away, as in `unitool qr … | head`.
- `UNITOOL_DEBUG=1` adds the stack trace to an internal error.
