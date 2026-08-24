import SwiftUI

/// Keyboard commands mirror the high-frequency desktop Compose actions.
struct ForumCommands: Commands {
    let store: ForumStore

    var body: some Commands {
        CommandMenu(String(localized: "forum.command.menu")) {
            Button(String(localized: "forum.search")) { store.focusSearch() }
                .keyboardShortcut("f", modifiers: .command)
            Button(String(localized: "forum.new_topic")) { store.openNewTopic() }
                .keyboardShortcut("n", modifiers: .command)
                .disabled(!store.state.canCreateTopic)
            Button(String(localized: "forum.refresh")) { store.refresh() }
                .keyboardShortcut("r", modifiers: .command)
            Divider()
            Button(String(localized: "forum.command.close")) { store.dismissTopmost() }
                .keyboardShortcut(.escape, modifiers: [])
        }
    }
}
