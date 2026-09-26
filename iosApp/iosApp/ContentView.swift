import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        // The library's scaffold pads for the safe area itself (WindowInsets.safeDrawing), as it
        // does edge-to-edge on Android; SwiftUI must not pad it a second time.
        ComposeView()
            .ignoresSafeArea(.all)
            // `unitool://activate?…` and `unitool://app/<id>/<path>` (06): into the same
            // consume-once route an Android intent and a notification tap use.
            .onOpenURL { url in
                UnitoolIos.shared.deliverLink(url: url.absoluteString)
            }
    }
}
