import SwiftUI

@main
struct WhispromptApp: App {
    init() {
        IntegrityGuard.check()
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
