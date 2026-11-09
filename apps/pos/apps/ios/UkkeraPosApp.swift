import SwiftUI
import UkkeraPos

// Each scene retains its own presentation/runtime. UIKit contributes no navigation history.
final class AppBridge: ObservableObject {
    let host = IosAppHost()
    deinit { host.close() }
}

struct NativePosView: UIViewControllerRepresentable {
    let bridge: AppBridge

    final class Coordinator {
        let bridge: AppBridge
        init(_ bridge: AppBridge) { self.bridge = bridge }
    }

    func makeCoordinator() -> Coordinator { Coordinator(bridge) }
    func makeUIViewController(context: Context) -> UIViewController { bridge.host.makeViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
    static func dismantleUIViewController(_ uiViewController: UIViewController, coordinator: Coordinator) {
        coordinator.bridge.host.releaseViewController(retiring: uiViewController)
    }
}

private struct PosSceneView: View {
    @StateObject private var bridge = AppBridge()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            NativePosView(bridge: bridge).ignoresSafeArea()
            if scenePhase != .active {
                Color(red: 0.08, green: 0.24, blue: 0.20).ignoresSafeArea()
                Text("Ukkera POS").foregroundStyle(.white).font(.title2)
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { bridge.host.resumeForForeground() }
            else { bridge.host.lockForBackground() }
        }
    }
}

@main
struct UkkeraPosApp: App {
    var body: some Scene {
        WindowGroup { PosSceneView() }
    }
}
