import SwiftUI

@main
struct FlareDoMacApp: App {
    var body: some Scene {
        WindowGroup {
            ForumShell()
        }
        .defaultSize(width: 1_080, height: 720)
    }
}
