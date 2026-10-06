package cx.m42.unitool.cli.app

import cx.m42.unitool.cli.Cli
import cx.m42.unitool.cli.qr.QrApp

/** Raised by hand with a release; `--version` prints it. */
internal const val VERSION: String = "0.1.0"

/** The apps of the console Unitool. A new one is a module under `unitool-cli/apps` and a line here. */
internal fun unitool(): Cli = Cli(name = "unitool", title = "Unitool", version = VERSION, apps = listOf(QrApp()))
