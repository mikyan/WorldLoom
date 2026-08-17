import SwiftUI
import UIKit
import WorldloomShared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(worldSources: loadContractWorldSources())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    private func loadContractWorldSources() -> [String] {
        let resourcePaths = [
            "contract-worlds/war-survival/world.json",
            "contract-worlds/station-ai/world.json",
        ]
        return resourcePaths.map { path in
            guard let url = Bundle.main.resourceURL?.appendingPathComponent(path) else {
                preconditionFailure("Bundle resource URL is unavailable")
            }
            do {
                return try String(contentsOf: url, encoding: .utf8)
            } catch {
                preconditionFailure("Unable to load contract world resource at \(path): \(error)")
            }
        }
    }
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
