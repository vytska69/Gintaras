import SwiftUI

/// Host app entry point. Lets the user preview the Lithuanian voice and configure
/// the reading settings (shared, via the App Group, with the system voice
/// extension). The system-wide voice itself is the GintarasVoice extension.
@main
struct GintarasApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
