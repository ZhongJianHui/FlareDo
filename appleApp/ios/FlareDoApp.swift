import SwiftUI

@main
struct FlareDoIOSApp: App {
    @StateObject private var store = ForumStore()

    var body: some Scene {
        WindowGroup {
            ForumShell(store: store)
        }
    }
}
