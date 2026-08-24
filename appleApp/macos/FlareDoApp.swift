import AppKit
import SwiftUI

@main
struct FlareDoMacApp: App {
    @NSApplicationDelegateAdaptor(FlareDoApplicationDelegate.self)
    private var applicationDelegate

    var body: some Scene {
        Window("FlareDo", id: "forum") {
            ForumShell(store: applicationDelegate.store)
        }
        .defaultSize(width: 1_080, height: 720)
        .commands {
            ForumCommands(store: applicationDelegate.store)
        }
    }
}

/// Owns the process-wide store and lets Kotlin finish its non-cancellable teardown before AppKit
/// terminates the process. Repeated terminate requests share the first in-flight close operation.
@MainActor
final class FlareDoApplicationDelegate: NSObject, NSApplicationDelegate {
    let store = ForumStore()

    private var isTerminationReplyPending = false

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard !isTerminationReplyPending else { return .terminateLater }
        isTerminationReplyPending = true
        store.close { [weak self, weak sender] in
            // `close` may finish synchronously when host creation failed. Queue the reply so this
            // delegate method always returns `.terminateLater` before AppKit receives the answer.
            Task { @MainActor [weak self, weak sender] in
                guard let self, self.isTerminationReplyPending else { return }
                self.isTerminationReplyPending = false
                sender?.reply(toApplicationShouldTerminate: true)
            }
        }
        return .terminateLater
    }
}
