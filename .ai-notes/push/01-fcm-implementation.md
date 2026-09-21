# Filling the push seam with FCM

*21 September 2026. Written after the fact, because the shape was decided by the library rather
than by this note.*

## Where the code went, and why it is not here

The obvious place for "add push to Unitool" is Unitool. It is the wrong place, and the library says
so in a comment written before any of this existed:

> Every actual is a no-op today. That is the whole design: the router, the topic store, the
> simulator in the Service Menu and the bench app's Push card all work against this, so adding FCM
> later is one `actual` file and a `google-services.json` — and if it is not, step 9b was done
> wrong.

`PushTransport` is an `expect object` in `superizer:host`. An application cannot supply an `actual`
for a library's `expect` — only the module that declares it can. So the transport had to be
implemented in Superizer, and it turned out to be almost exactly the one file that comment predicts.

That is the test of the seam, and it passed: `PushRouter`, `TopicStore`, `RoutedPush` and every one
of their tests are untouched. The diff that made push real does not appear in any file that decides
what a push *means*.

## The split

**Superizer** owns the machinery, because it is the same on every host:

* `PushTransport.android.kt` — token, topics, permission. `available` became a computed property
  rather than a constant, because it is a fact about the *app* the library was linked into and the
  library cannot know it at compile time.
* `SuperizerMessagingService` — the class FCM constructs by name. It hands the data map to the
  transport and does nothing else; a second copy of the routing rules living there is how the
  Android build would quietly start behaving differently from the tests.
* The library's `AndroidManifest.xml` — `INTERNET`, `POST_NOTIFICATIONS`, and the service. Merged
  into every host that depends on it.

**Unitool** owns the two things that cannot be in a library:

* `google-services.json`, which names one Play package and is a per-deployment credential.
* `AndroidNotifier`, which draws. `Notifier` is a hole in the host builder precisely because
  `NotificationManager` exists on one of the three targets.

## Two things that needed deciding

**The permission dialog needs an Activity, and the seam has none.** `PushTransport` is reached from
a `FirebaseMessagingService` and from the router, neither of which has one — that is why it is an
object. So `AndroidHost` now keeps a *weak* reference to the activity, set by the same `init` call
it already had, and `MainActivity` forwards `onRequestPermissionsResult` back. Weak because the
object outlives every activity; forwarded blind because the transport ignores a request code that
is not its own.

**A cold-start data message is dropped.** `incoming` has no replay, so a message that arrives while
no host is collecting goes nowhere. This is not worth fixing in the transport: the answer is a
`notification` payload alongside the data, which Firebase displays without any of our code running.
A data-only message is for an app that is already open. Written down because the failure looks like
a bug and is a design decision.

## What it cost outside the code

The app was not offline before this — the converter already fetched rates — but it had no
`INTERNET` permission, so those requests had been failing. The privacy policy and both store
listings were written against "no network permission, nothing leaves the phone", and all three were
false in one direction before and would have been false in the other direction after. They were
rewritten against the built APK's permission list rather than against the source, because three of
the six permissions are added by Firebase and a list written from memory would omit exactly those.

See [[superizer-config-bundle-plan]] for the other half of the host's per-app configuration story.
