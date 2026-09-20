package cx.m42.unitool.platform

import androidx.compose.runtime.Composable

/**
 * Intercepts the platform's own "go back", where the platform has one.
 *
 * Android does: the gesture from the screen edge, and the button before it. Left unhandled, both
 * finish the activity — so a user on Settings, or with the drawer open, is dropped out of the app
 * rather than taken one step back. That is the single most visible way a Compose app can feel
 * unfinished, and it is not something the shared UI can fix on its own, so it joins haptics and
 * the device language as a thing each target answers for itself.
 *
 * Desktop and the browser have no equivalent gesture and do nothing.
 *
 * Handlers nest: the innermost enabled one wins, so the drawer's claim on back is registered
 * deeper than the navigation's and closes the drawer before the navigation sees anything.
 *
 * @param enabled false unregisters the handler entirely rather than swallowing the event — a
 *   disabled handler must let back mean what it meant before there was one.
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
