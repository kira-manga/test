import SwiftUI
import UkkeraPos

// Retained scene composition root. No NavigationStack/UINavigationController is layered over Nav3.
final class AppBridge: ObservableObject {
    let host = IosAppHost()
    deinit { host.close() }
}

struct NativePosView: UIViewControllerRepresentable {
    let bridge: AppBridge
    func makeUIViewController(context: Context) -> UIViewController { bridge.host.makeViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct UkkeraPosApp: App {
    @StateObject private var bridge = AppBridge()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ZStack {
                NativePosView(bridge: bridge).ignoresSafeArea()
                if scenePhase != .active {
                    Color(red: 0.08, green: 0.24, blue: 0.20).ignoresSafeArea()
                    Text("Ukkera POS").foregroundStyle(.white).font(.title2)
                }
            }
            .onChange(of: scenePhase) { phase in
                if phase != .active { bridge.host.lockForBackground() }
            }
        }
    }
}
