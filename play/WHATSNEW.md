# Release notes, as Play wants them

*This file deliberately does **not** live in `play/whatsnew/`.* Keeping that directory to locale
files only is a habit rather than a requirement — the upload action does filter on the `whatsnew-`
prefix, verified by a run that had a `README.md` sitting in there and published cleanly anyway.

`android-play.yml` passes `play/whatsnew/` to the upload action as `whatsNewDirectory`, so whatever
is in these files is what Play shows under **What's new** for the release that run creates.

## What is actually true about the filenames

* **No extension.** The action reads the locale out of everything after `whatsnew-`, so
  `whatsnew-en-US.txt` would ask Play for a locale called `en-US.txt`.
* **The limit is 500 characters** per locale, counted after the upload finishes.
* **A locale does not have to be in the store listing.** This was the assumption worth writing
  down, because it was assumed the other way round first. The listing for `cx.m42.unitool` has
  exactly one locale, `en-US` — `edits.listings.list` was asked and answered — and the release
  notes for `ru-RU` were nonetheless accepted and are visible on the release. Store listings and
  release notes are separate things in the API, and Play does not require the second to be a subset
  of the first.

  The Russian notes were pulled out of this directory on the strength of the opposite guess. They
  are back, because the run proved the guess wrong. Prefer the run to the guess.

## Still worth doing

The app ships a Russian interface and the finished Russian store copy sits in
`play/listing/description-ru.md`, but the listing itself is English-only. That is a real gap, and
it closes once in **Play Console → Main store listing → Manage translations → Add your own
translation → Русский**. It is a publishing decision, not something CI should do on anyone's
behalf, so nothing here does it.

These notes are edited by a person before a release — the one part of a publish that is written
rather than computed. A file that still describes the previous version is worse than an empty one.
