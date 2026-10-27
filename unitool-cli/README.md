# unitool-cli — Unitool in the terminal

A console tool in Kotlin. Like the phone, it is a set of apps; here an app is a word on the command
line with commands under it: `unitool qr encode …`. It lives in this repository on purpose: the phone
and the terminal are one codebase and cannot drift apart. The design is in
[`.ai-notes/done/cli`](../.ai-notes/done/cli/README.md).

Today there are four apps: `qr`, `split`, `password` and `browser`.

## Running it

```sh
unitool-cli/bin/unitool --help                 # builds itself on first run (or when a source changed)
ln -s "$PWD/unitool-cli/bin/unitool" ~/bin/     # optional: `unitool` from anywhere

./gradlew :unitool-cli:app:install             # or build by hand: unitool-cli/app/build/dist/unitool
```

`unitool` is a native Linux executable (Kotlin/Native, `linuxX64`). It is one file of about 1.4 MB,
needs no JVM and starts in about 30 ms. Copy `build/dist/unitool` anywhere and it runs. It links only
glibc 2.19 or newer, which every Linux has.

A fully static file, with nothing from the system at all, is a second task:

```sh
./gradlew :unitool-cli:app:installStatic       # build/dist-static/unitool-linux-x86_64 + .sha256
```

Kotlin/Native cannot link that by itself, so `app/static-ld.sh` stands in for its linker and
rewrites the link line. The file is about 2.6 MB. CI builds it on every push that touches the CLI or
the kit (`.github/workflows/cli.yml`) and keeps it as the run's artifact, with the SHA-256 in the
artifact and in the run's summary.

Building it needs the same setup as the phone build (a JDK for Gradle, the Android SDK in
`local.properties`), because Gradle configures the whole project. The first build downloads
Kotlin/Native's Linux toolchain once. A release link takes about a minute.

## Shared services: `unitool-kit`

What a command does that is not about the command line lives in the shared module `:unitool-kit`
(`/unitool-kit`), which the phone can use too. The CLI apps only parse parameters, pick where the
result goes and word the messages. Today the kit has the QR service:

```kotlin
val code = Qr.encode("https://m42.cx", ErrorCorrection.L) ?: error("too long")
QrImage.png(code)        // a picture: PNG bytes (deflate included, no zlib needed)
QrImage.svg(code)        // a picture: SVG text
QrTerminal.ansi(code)    // terminal output
Qr.split(text)           // text too long for one code, cut on line breaks
QrFile.cut(name, bytes)  // a file as UQR1 chunks; QrFileGathering puts it back
```

It also has the cryptography the phone's SSH keyring stands on (`kit.crypto`), the `ucrypt` file
format (`kit.crypt`) and secret sharing (`kit.split`).

Terminal services every command can use are in `core`: `Io.showScreens(...)` shows several screens
one at a time (Enter between them, read from `/dev/tty` even behind a pipe), `Io.cycle(...)` calls a
tick on an interval until Enter, and `CLEAR_SCREEN`.

## Tests

```sh
./gradlew :unitool-kit:desktopTest :unitool-kit:linuxX64Test \
    :unitool-cli:core:allTests :unitool-cli:apps:qr:allTests :unitool-cli:apps:split:allTests :unitool-cli:app:allTests
```

The CLI's tests run twice, on the JVM and natively (`linuxX64Test`); the kit's on the desktop JVM and
natively. The JVM adds four checks:
- `QrZxingTest` (kit) compares the hand-written encoder with ZXing module for module, for every
  version and level, and reads every rendering back;
- `DeflateTest` (kit) reads the hand-written deflate back with `java.util.zip.Inflater`;
- `NativeBinaryTest` (qr) runs the shipped binary and compares its output with the kit's, byte for
  byte (build it first with `:unitool-cli:app:install`; without it the test is skipped);
- `NativeBinaryTest` (split) runs it with real files: 0600, nothing replaced, PNG parts that ZXing
  scans and that open the container.

Step 2b of `./scripts/verify.sh` does all of this and also calls the binary from the shell.

## `unitool qr` — QR codes from text

```sh
unitool qr 'https://m42.cx'                    # to the terminal, black on white whatever the theme
echo 'from a pipe' | unitool qr                # no TEXT: stdin, minus the final line break
unitool qr 'hello' -o code.png                 # the format follows the extension: .png, .svg
unitool qr 'hello' -f svg > code.svg           # or is named; PNG is refused on a terminal
unitool qr 'hello' --ec H --scale 12 -o big.png

# Text longer than one code (2953 bytes at --ec L) is cut on line breaks with --split. At a terminal
# the codes come one at a time, with Enter in between (the keyboard is read from /dev/tty, so the text
# can come from a pipe). With -o they become export-1.png, export-2.png, …
age -d -i ~/.ssh/id_ed25519 2fa-export.age | unitool qr --split

# Any file, text or not, up to 1 MiB: cut into UQR1 chunks (base45 in the alphanumeric mode, name and
# SHA-256 inside) that Unitool's QR scanner gathers in any order. --cycle shows them in a loop until
# Enter, so the phone just keeps the camera on the screen; --chunk makes the codes smaller or larger.
unitool qr --file photo.jpg --cycle
unitool qr --file notes.pdf --chunk 600 --interval 0.3 --cycle
unitool qr --file notes.pdf -o out/notes.png   # notes-1.png, notes-2.png, …
```

`unitool` with no arguments at a terminal opens a menu: pick an app, a command, answer the questions.
At the end it prints the same call as one command line.

## `unitool split` — a secret in parts

The secret is encrypted into a container (`ucrypt`, a file of its own) under a random key, and the key
is split so that any K of N parts open it and fewer tell nothing at all (Shamir's secret sharing). Each
part is a QR code of an ordinary-looking link with the part after `#`, which browsers never send to
a server. The design is in [`.ai-notes/done/split`](../.ai-notes/done/split/README.md).

```sh
unitool split                    # at a terminal: a wizard, step by step, ending with the command below
printf 'my secret' | unitool split make -n 3 -k 2 --base https://example.com/p -o secret.ucrypt --show png
unitool split join secret.ucrypt -p part-1-of-3.txt -p part-3-of-3.txt
unitool split join secret.ucrypt     # asks for the parts one at a time, hidden as you type
unitool split inspect secret.ucrypt  # what a container or a part is, without opening it
```

- From 2 to 5 parts; the link address is yours to choose, and the page there must not run scripts.
- Parts go to the screen one at a time (the screen and its scrollback are cleared after each), or to
  one file per part: `png`, `svg` or `txt`.
- Every file it writes is readable by its owner only (0600), and a container or a part is never
  replaced: it may be the only copy.
- Secrets never come from argv: stdin, files, or a question that does not echo.

## Conventions

- **Everything the tool says is in English**: help, menu, messages. (The design notes in `.ai-notes/done/cli`
  stay in Russian, as all notes in this repository do.)
- **stdout carries only the result** (the code, the PNG bytes); every message goes to stderr. Pipes and
  redirects therefore just work.
- **Exit codes:** 0 for success, 1 when the command could not do it (text too long, a file that will not
  write), 2 for a wrong call (unknown option, bad value), 130 when you leave the menu with Ctrl-D,
  141 (silently) when the reader of stdout goes away, as in `unitool qr … | head`.
- `UNITOOL_DEBUG=1` adds the stack trace to an internal error.

## Adding an app

1. Create a module `unitool-cli/apps/<id>`, Kotlin Multiplatform with `jvm()` and `linuxX64()`,
   depending on `:unitool-cli:core` (and `:unitool-kit` for shared services). See
   `apps/qr/build.gradle.kts`. The code goes in `commonMain`; a JVM-only library can only be used in
   tests, because the binary is native. Logic that is not about the command line goes to the kit.
2. Write a `CliApp` with its `Command`s. Parameters are properties:

   ```kotlin
   private val text by argument("TEXT", "the text; without it, stdin is read")
   private val ec by option("--ec", help = "error correction", metavar = "LEVEL").choice("L", "M", "Q", "H").default("L")
   private val split by flag("--split", help = "cut on line breaks").advanced()   // not asked in the menu
   ```

   That one declaration is the parser, `--help` and the menu. An option given several times is
   `.repeated()`, a `List<String>`.

   When the choices depend on each other, the app can also have a `wizard`: then `unitool <id>`
   alone at a terminal runs it instead of the generic menu. It asks with `answer`, `hidden` (no
   echo), `number`, `pick` and `yes` from `core`, does its work through the same code as the
   commands, and ends with the command line that repeats it (`shellLine`). See `apps/split`.
3. Add it to `listOf(...)` in `app/src/commonMain/kotlin/.../Unitool.kt`, and its `allTests` to `verify` in the root
   `build.gradle.kts` and step 2b of `scripts/verify.sh`.
4. Test with `FakeIo` and `cli.exec(...)` from `:unitool-cli:testing`. See `apps/qr/src/commonTest`.

A CLI module may not depend on an app, a service, Superizer or Compose. If it needs code the phone
already has, that code moves to `:unitool-kit`, which depends on nothing (`checkDependencyRules`).

## `unitool browser` — passwords from the phone into the browser

The computer's side of [`.ai-notes/passwords-browser`](../.ai-notes/passwords-browser/README.md). The
Unitool extension (`unitool-browser/`) starts `unitool browser host`; it finds the phone in the
Wi-Fi (its own mDNS query, no Avahi), talks Noise to it, and opens the phone's answer — age to this
computer's SSH key — with the key file in `~/.ssh`.

```sh
unitool browser install                        # manifests for Chrome, Chromium, Brave, Edge, Firefox + the wrapper they start
unitool browser install --extension-id ID      # also allow a store's extension id
unitool browser pair                           # a QR code here; Unitool → Passwords → Computers on the phone
unitool browser pair --key ~/.ssh/other_key    # Ed25519 or RSA ≥ 2048, without a passphrase (D545)
unitool browser devices                        # the paired phones and the key in use
unitool browser forget ID
```

What it keeps: `~/.config/unitool/link/{key,phones,settings}`, 0600 in a 0700 directory — its link key,
the phones, the key's path. Never a password and never the pairing secret. Tests: `BrowserHostTest`
(JVM and linuxX64, against a phone made of the kit) and, in `services/link`, `NativeHostTest` (this
binary against the desktop listener over the real network).
