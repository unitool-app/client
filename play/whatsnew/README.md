# Release notes, as Play wants them

`android-play.yml` passes this directory to the upload action as `whatsNewDirectory`, so whatever
is in these files is what Play shows under **What's new** for the release that run creates.

Two rules the filenames have to follow, both of which fail quietly rather than loudly:

* **No extension.** The action reads the locale out of everything after `whatsnew-`, so
  `whatsnew-en-US.txt` asks Play for a locale called `en-US.txt` and the notes are dropped.
* **The locale must be one the listing has.** A locale Play has never seen for this app is
  rejected with the upload, not with the file.

The limit is **500 characters** per locale. Play counts them after the upload has finished.

These are edited by a person before a release — they are the one part of a publish that is written
rather than computed, and a file that still describes the previous version is worse than an empty
one. If a run should publish no notes at all, delete the files; the action skips a directory with
nothing in it.
