import SwiftUI
import RFMapperCore
import RFMapperCollectorKit

/// The iOS Collector.
///
/// Deliberately a *supervised survey* tool rather than an attempt at an all-day unattended
/// collector. iOS has no equivalent of Android's foreground service, and unfiltered BLE scanning
/// stops the moment the app leaves the foreground, so a multi-hour unattended session is not
/// achievable with public APIs (`docs/06-ios-capability-matrix.md` §4). The interface is built
/// around that constraint instead of hiding it: the session screen tells the operator what the
/// current mode can and cannot see, and keeps the screen awake while collecting.
@main
struct RFMapperCollectorApp: App {

    @StateObject private var model = CollectorModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
        }
    }
}

struct RootView: View {

    @EnvironmentObject private var model: CollectorModel

    var body: some View {
        TabView {
            SessionView()
                .tabItem { Label("Collect", systemImage: "dot.radiowaves.left.and.right") }
            LiveFeedView()
                .tabItem { Label("Live", systemImage: "list.bullet") }
            ExportView()
                .tabItem { Label("Export", systemImage: "square.and.arrow.up") }
            ObserverView()
                // "iphone", not "iphone.gen3": the generational variants arrived in SF Symbols 4.2
                // with iOS 16.4, and this target deploys to 16.0, where the tab would render blank.
                .tabItem { Label("Observer", systemImage: "iphone") }
        }
        .task { model.prepare() }
    }
}
