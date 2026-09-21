# Release notes, as Play wants them

*This file deliberately does **not** live in `play/whatsnew/`.*

`android-play.yml` passes this directory to the upload action as `whatsNewDirectory`, so whatever
is in these files is what Play shows under **What's new** for the release that run creates.

Three rules, all of which fail quietly rather than loudly:

* **Nothing but locale files in that directory.** The upload action reads the directory and turns
  each entry into a locale. A `README.md` sitting there is an attempt to publish release notes in
  a language called `README.md`, which is why this explanation is one level up.

* **No extension.** The action reads the locale out of everything after `whatsnew-`, so
  `whatsnew-en-US.txt` asks Play for a locale called `en-US.txt` and the notes are dropped.
* **The locale must be one the listing has.** Right now that is `en-US` and nothing else —
  `edits.listings.list` was asked. A locale the listing does not have is rejected with the upload,
  not with the file, so it fails the publish step of an otherwise green run after the bundle is
  already built. The finished Russian notes are parked in `play/listing/pending/` for exactly this
  reason; see the README there.

The limit is **500 characters** per locale. Play counts them after the upload has finished.

These are edited by a person before a release — they are the one part of a publish that is written
rather than computed, and a file that still describes the previous version is worse than an empty
one. If a run should publish no notes at all, delete the files; the action skips a directory with
nothing in it.
