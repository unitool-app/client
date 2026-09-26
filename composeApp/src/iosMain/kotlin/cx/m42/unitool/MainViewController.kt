package cx.m42.unitool

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The whole app as one UIKit controller, which `iosApp/ContentView.swift` wraps for SwiftUI. The
 * host is built once per process (see [UnitoolIos]); a second controller — SwiftUI may make one on
 * a scene reconnect — draws the same host rather than a second one.
 */
fun MainViewController(): UIViewController = ComposeUIViewController { UnitoolApp(UnitoolIos.host(), UnitoolIos.security) }
