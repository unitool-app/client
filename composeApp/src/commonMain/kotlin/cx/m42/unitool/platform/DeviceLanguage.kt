package cx.m42.unitool.platform

/**
 * The BCP-47 tag of the language this device is set to — `ru`, `en-GB`.
 *
 * Only ever a *fallback*: which language the app speaks is the choice stored on this device, and
 * this answers the question only while nobody has made one. An empty string is a legal answer and
 * means "could not tell", which reads as "no opinion" rather than as a language.
 */
expect fun deviceLanguageTag(): String
