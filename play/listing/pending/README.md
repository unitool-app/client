# Written, but not yet accepted by Play

## `whatsnew-ru-RU`

The Play listing for `cx.m42.unitool` currently has **one** locale: `en-US`. Asked directly:

```
edits.listings.list -> ['en-US']
```

Release notes for a locale the listing does not have are rejected with the upload, not with the
file — so a `whatsnew-ru-RU` sitting in `play/whatsnew/` fails the publish step of an otherwise
green run, at the very end, after the bundle is built.

The text is finished and `play/listing/description-ru.md` is finished too. What is missing is the
Russian listing itself, which is created once in **Play Console → Main store listing → Manage
translations → Add your own translation → Русский**, filled from `description-ru.md`.

Move this file back into `play/whatsnew/` the same day that happens. Until then the app ships a
Russian interface with an English-only store page, which is a real gap — just not one CI can close
without publishing store copy on somebody's behalf.
