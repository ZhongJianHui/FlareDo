import KotlinSharedUI
import SwiftUI

/// Early forum shell shared by iOS and macOS.
///
/// It owns navigation and visual structure only. Networking, persistence, authentication, and
/// presenters remain Kotlin-owned boundaries and are connected in later stages.
struct ForumShell: View {
    @State private var selection = ForumSection.latest

    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    private let productName = AppleSharedHelper.shared.productName()

    var body: some View {
        #if os(iOS)
        if horizontalSizeClass == .regular {
            splitView
        } else {
            compactTabs
        }
        #else
        splitView
        #endif
    }

    private var splitView: some View {
        NavigationSplitView {
            ForumSidebar(
                productName: productName,
                selection: $selection
            )
            #if os(macOS)
            .navigationSplitViewColumnWidth(min: 196, ideal: 224, max: 280)
            #endif
        } detail: {
            NavigationStack {
                ForumSectionPlaceholder(section: selection)
                    .navigationTitle(selection.title)
            }
        }
    }

    #if os(iOS)
    private var compactTabs: some View {
        TabView(selection: $selection) {
            ForEach(ForumSection.allCases) { section in
                NavigationStack {
                    ForumSectionPlaceholder(section: section)
                        .navigationTitle(productName)
                        .navigationBarTitleDisplayMode(.inline)
                }
                .tabItem {
                    Label(section.title, systemImage: section.systemImage)
                }
                .tag(section)
            }
        }
        .tint(ForumPalette.teal)
    }
    #endif
}

private struct ForumSidebar: View {
    let productName: String
    @Binding var selection: ForumSection

    var body: some View {
        List {
            Section {
                ForEach(ForumSection.allCases) { section in
                    Button {
                        selection = section
                    } label: {
                        Label(section.title, systemImage: section.systemImage)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .foregroundStyle(selection == section ? section.accent : .primary)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(
                        selection == section
                            ? section.accent.opacity(0.12)
                            : Color.clear
                    )
                    .accessibilityAddTraits(selection == section ? .isSelected : [])
                }
            } header: {
                ForumBrandLabel(productName: productName)
                    .padding(.bottom, 8)
                    .textCase(nil)
            }
        }
        .listStyle(.sidebar)
        .navigationTitle(productName)
    }
}

private struct ForumBrandLabel: View {
    let productName: String

    var body: some View {
        HStack(spacing: 10) {
            Image("BrandMark")
                .resizable()
                .interpolation(.high)
                .frame(width: 30, height: 30)
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            Text(productName)
                .font(.system(.headline, design: .rounded, weight: .semibold))
                .foregroundStyle(.primary)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct ForumSectionPlaceholder: View {
    let section: ForumSection

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: section.systemImage)
                .font(.system(size: 34, weight: .medium))
                .foregroundStyle(section.accent)
                .frame(width: 48, height: 48)
            Text(section.title)
                .font(.title3.weight(.semibold))
            Text(section.emptyState)
                .font(.callout)
                .foregroundStyle(.secondary)
        }
        .multilineTextAlignment(.center)
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.primary.opacity(0.025))
    }
}

private enum ForumSection: String, CaseIterable, Identifiable {
    case latest
    case hot
    case categories

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .latest:
            "forum.latest"
        case .hot:
            "forum.hot"
        case .categories:
            "forum.categories"
        }
    }

    var emptyState: LocalizedStringKey {
        switch self {
        case .latest, .hot:
            "forum.empty.topics"
        case .categories:
            "forum.empty.categories"
        }
    }

    var systemImage: String {
        switch self {
        case .latest:
            "clock.arrow.circlepath"
        case .hot:
            "flame.fill"
        case .categories:
            "square.grid.2x2.fill"
        }
    }

    var accent: Color {
        switch self {
        case .latest:
            ForumPalette.teal
        case .hot:
            ForumPalette.coral
        case .categories:
            ForumPalette.gold
        }
    }
}

private enum ForumPalette {
    static let teal = Color(red: 0.06, green: 0.46, blue: 0.43)
    static let coral = Color(red: 0.92, green: 0.35, blue: 0.28)
    static let gold = Color(red: 0.76, green: 0.53, blue: 0.10)
}
