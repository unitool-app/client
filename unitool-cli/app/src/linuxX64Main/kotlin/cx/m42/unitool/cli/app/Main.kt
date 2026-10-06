package cx.m42.unitool.cli.app

import cx.m42.unitool.cli.SystemIo
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(unitool().run(args.toList(), SystemIo()))
}
