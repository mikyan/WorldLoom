import SwiftUI
import UIKit
import WorldloomShared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            manifestSources: loadContractSources(fileName: "manifest.json"),
            worldSources: loadContractSources(fileName: "world.json")
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    private func loadContractSources(fileName: String) -> [String] {
        let resourcePaths = ["war-survival", "station-ai"].map {
            "contract-worlds/\($0)/\(fileName)"
        }
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
