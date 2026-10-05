import SwiftUI
import Shared

@main
struct RideRangeApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // Compose draws edge to edge and handles the safe area and keyboard itself.
                .ignoresSafeArea(.all)
        }
    }
}

/// The shared Compose Multiplatform UI (all five tabs).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(mapFactory: MapLibreFactory())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
