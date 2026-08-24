import KotlinSharedUI
import SwiftUI
import XCTest

@testable import FlareDo

/// Network-free layout verification for the native Apple shells.
///
/// These tests verify dimensions and alpha-aware rendered pixels, and retain images in the XCTest
/// result bundle for visual review. A missing framework, blank hierarchy, or empty split region then
/// fails with a useful assertion instead of accepting a transparent or single-color render.
nonisolated final class ForumShellLayoutTests: XCTestCase {
    @MainActor
    func testRealtimeRecoveryReasonMapsEverySharedValue() {
        XCTAssertEqual(
            AppleSessionRecoveryReason.authenticationRequired.swiftValue,
            .authenticationRequired
        )
        XCTAssertEqual(
            AppleSessionRecoveryReason.permissionDenied.swiftValue,
            .permissionDenied
        )
        XCTAssertEqual(
            AppleSessionRecoveryReason.manualChallengeRequired.swiftValue,
            .manualChallengeRequired
        )
    }

    @MainActor
    func testFixtureRecoveryGatesLoginUntilOwnerSignOutCompletes() {
        let authenticatedStore = ForumStore(fixture: .preview)
        XCTAssertFalse(authenticatedStore.canBeginAuthentication)

        var state = ForumViewState.preview
        state.realtimeRecoveryReason = .authenticationRequired
        let store = ForumStore(fixture: state)

        XCTAssertFalse(store.canBeginAuthentication)
        store.beginLogin()
        store.beginFallbackLogin()
        XCTAssertEqual(store.state.realtimeRecoveryReason, .authenticationRequired)

        store.logout()
        XCTAssertNil(store.state.realtimeRecoveryReason)
        XCTAssertFalse(store.state.isAuthenticated)
        XCTAssertNil(store.state.accountUsername)
        XCTAssertFalse(store.state.canCreateTopic)
        XCTAssertTrue(store.canBeginAuthentication)
    }

    #if os(iOS)
    @MainActor
    func testIPhoneCompactImageRendererIsNonblank() throws {
        let image = try renderIOS(width: 390, height: 844, sizeClass: .compact)
        XCTAssertEqual(image.size.width, 390, accuracy: 1)
        XCTAssertEqual(image.size.height, 844, accuracy: 1)
        XCTAssertTrue(try image.cgImageValue().containsVisibleVariation())
        retain(image, name: "iPhone compact topic")
    }

    @MainActor
    func testIPadRegularImageRendererIsNonblankAtAccessibilitySize() throws {
        let image = try renderIOS(
            width: 1_024,
            height: 768,
            sizeClass: .regular,
            dynamicTypeSize: .accessibility2
        )
        XCTAssertEqual(image.size.width, 1_024, accuracy: 1)
        XCTAssertEqual(image.size.height, 768, accuracy: 1)
        let cgImage = try image.cgImageValue()
        XCTAssertTrue(cgImage.containsVisibleVariation())
        XCTAssertTrue(cgImage.containsVisibleVariation(in: CGRect(x: 0, y: 0, width: 0.45, height: 1)))
        XCTAssertTrue(cgImage.containsVisibleVariation(in: CGRect(x: 0.55, y: 0, width: 0.45, height: 1)))
        retain(image, name: "iPad regular accessibility")
    }

    @MainActor
    func testIPhoneCompactProfileWrapsAtAccessibilitySizeInChinese() throws {
        var state = ForumViewState.preview
        state.destination = .profile
        state.selectedTopicID = nil
        state.selectedTopic = nil
        let image = try renderIOS(
            width: 390,
            height: 844,
            sizeClass: .compact,
            dynamicTypeSize: .accessibility2,
            colorScheme: .dark,
            locale: Locale(identifier: "zh-Hans"),
            state: state
        )

        XCTAssertTrue(try image.cgImageValue().containsVisibleVariation())
        retain(image, name: "iPhone compact Chinese profile accessibility dark")
    }

    @MainActor
    func testIPhoneCompactRecoveryBannerIsNonblank() throws {
        var state = ForumViewState.preview
        state.realtimeRecoveryReason = .authenticationRequired
        let image = try renderIOS(
            width: 390,
            height: 844,
            sizeClass: .compact,
            state: state
        )

        let cgImage = try image.cgImageValue()
        XCTAssertTrue(cgImage.containsVisibleVariation())
        XCTAssertTrue(cgImage.containsVisibleVariation(in: CGRect(x: 0, y: 0, width: 1, height: 0.2)))
        retain(image, name: "iPhone compact realtime recovery")
    }

    @MainActor
    func testIPadRegularRecoveryBannerFitsAccessibilityText() throws {
        var state = ForumViewState.preview
        state.realtimeRecoveryReason = .permissionDenied
        let image = try renderIOS(
            width: 1_024,
            height: 768,
            sizeClass: .regular,
            dynamicTypeSize: .accessibility2,
            locale: Locale(identifier: "zh-Hans"),
            state: state
        )

        let cgImage = try image.cgImageValue()
        XCTAssertTrue(cgImage.containsVisibleVariation())
        XCTAssertTrue(cgImage.containsVisibleVariation(in: CGRect(x: 0, y: 0, width: 1, height: 0.25)))
        retain(image, name: "iPad regular realtime recovery accessibility")
    }

    @MainActor
    func testFixtureNavigationAndNotificationActionsPreserveExpectedState() {
        let store = ForumStore(fixture: .preview)

        store.selectDestination(.profile)
        XCTAssertEqual(store.state.destination, .profile)
        XCTAssertNil(store.state.selectedTopicID)
        XCTAssertNil(store.state.selectedTopic)

        store.markNotificationRead(nil)
        XCTAssertEqual(store.state.unreadNotificationCount, 0)
        XCTAssertTrue(store.state.notifications.allSatisfy { !$0.unread })
    }

    @MainActor
    private func renderIOS(
        width: CGFloat,
        height: CGFloat,
        sizeClass: UserInterfaceSizeClass,
        dynamicTypeSize: DynamicTypeSize = .large,
        colorScheme: ColorScheme = .light,
        locale: Locale = Locale(identifier: "en"),
        state: ForumViewState = .preview
    ) throws -> UIImage {
        let store = ForumStore(fixture: state)
        let content = ForumShell(store: store)
            .environment(\.horizontalSizeClass, sizeClass)
            .environment(\.dynamicTypeSize, dynamicTypeSize)
            .environment(\.colorScheme, colorScheme)
            .environment(\.locale, locale)
            .frame(width: width, height: height)
        let renderer = ImageRenderer(content: content)
        renderer.scale = 1
        renderer.proposedSize = ProposedViewSize(width: width, height: height)
        return try XCTUnwrap(renderer.uiImage)
    }

    @MainActor
    private func retain(_ image: UIImage, name: String) {
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
    #elseif os(macOS)
    @MainActor
    func testMacSplitViewNSHostingViewIsNonblank() throws {
        let bitmap = try renderMac(width: 1_080, height: 720, colorScheme: .light)

        // NSHostingView renders at the active backing scale, so pixelsWide/pixelsHigh are 2x on
        // Retina displays. NSBitmapImageRep.size remains the requested logical window size.
        XCTAssertEqual(bitmap.size.width, 1_080, accuracy: 1)
        XCTAssertEqual(bitmap.size.height, 720, accuracy: 1)
        let image = try bitmap.cgImageValue()
        XCTAssertTrue(image.containsVisibleVariation())
        XCTAssertTrue(image.containsVisibleVariation(in: CGRect(x: 0, y: 0, width: 0.45, height: 1)))
        XCTAssertTrue(image.containsVisibleVariation(in: CGRect(x: 0.55, y: 0, width: 0.45, height: 1)))
    }

    @MainActor
    func testMacNarrowDarkWindowRemainsNonblank() throws {
        let bitmap = try renderMac(width: 700, height: 500, colorScheme: .dark)
        XCTAssertEqual(bitmap.size.width, 700, accuracy: 1)
        XCTAssertEqual(bitmap.size.height, 500, accuracy: 1)
        XCTAssertTrue(try bitmap.cgImageValue().containsVisibleVariation())
    }

    @MainActor
    func testMacSplitRecoveryBannerFitsWindow() throws {
        var state = ForumViewState.preview
        state.realtimeRecoveryReason = .manualChallengeRequired
        let bitmap = try renderMac(
            width: 1_080,
            height: 720,
            colorScheme: .light,
            state: state
        )

        let image = try bitmap.cgImageValue()
        XCTAssertTrue(image.containsVisibleVariation())
        XCTAssertTrue(image.containsVisibleVariation(in: CGRect(x: 0, y: 0, width: 1, height: 0.2)))
    }

    @MainActor
    private func renderMac(
        width: CGFloat,
        height: CGFloat,
        colorScheme: ColorScheme,
        state: ForumViewState = .preview
    ) throws -> NSBitmapImageRep {
        let size = CGSize(width: width, height: height)
        let store = ForumStore(fixture: state)
        let hosting = NSHostingView(
            rootView: ForumShell(store: store)
                .environment(\.colorScheme, colorScheme)
                .frame(width: size.width, height: size.height)
        )
        hosting.frame = CGRect(origin: .zero, size: size)
        hosting.layoutSubtreeIfNeeded()

        let bitmap = try XCTUnwrap(
            hosting.bitmapImageRepForCachingDisplay(in: hosting.bounds)
        )
        hosting.cacheDisplay(in: hosting.bounds, to: bitmap)
        return bitmap
    }
    #endif
}

#if os(iOS)
private extension UIImage {
    func cgImageValue() throws -> CGImage {
        try XCTUnwrap(cgImage)
    }
}
#elseif os(macOS)
private extension NSBitmapImageRep {
    func cgImageValue() throws -> CGImage {
        try XCTUnwrap(cgImage)
    }
}
#endif

private extension CGImage {
    /// Draws into a known RGBA layout, then requires visibly different nontransparent samples.
    func containsVisibleVariation(in normalizedRegion: CGRect = CGRect(x: 0, y: 0, width: 1, height: 1)) -> Bool {
        let rowBytes = width * 4
        var pixels = [UInt8](repeating: 0, count: rowBytes * height)
        return pixels.withUnsafeMutableBytes { storage in
            guard let baseAddress = storage.baseAddress,
                  let context = CGContext(
                      data: baseAddress,
                      width: width,
                      height: height,
                      bitsPerComponent: 8,
                      bytesPerRow: rowBytes,
                      space: CGColorSpaceCreateDeviceRGB(),
                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                  ) else { return false }
            context.draw(self, in: CGRect(x: 0, y: 0, width: width, height: height))

            let region = normalizedRegion.standardized.intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
            guard !region.isNull, !region.isEmpty else { return false }
            let minimumX = min(width - 1, max(0, Int(region.minX * CGFloat(width))))
            let maximumX = min(width, max(minimumX + 1, Int(region.maxX * CGFloat(width))))
            let minimumY = min(height - 1, max(0, Int(region.minY * CGFloat(height))))
            let maximumY = min(height, max(minimumY + 1, Int(region.maxY * CGFloat(height))))
            let stepX = max(1, (maximumX - minimumX) / 32)
            let stepY = max(1, (maximumY - minimumY) / 32)
            var minimumLuma = Int.max
            var maximumLuma = Int.min
            var visibleSamples = 0

            for y in stride(from: minimumY, to: maximumY, by: stepY) {
                for x in stride(from: minimumX, to: maximumX, by: stepX) {
                    let index = y * rowBytes + x * 4
                    let alpha = Int(storage[index + 3])
                    guard alpha >= 16 else { continue }
                    let luma = (Int(storage[index]) * 54 + Int(storage[index + 1]) * 183 + Int(storage[index + 2]) * 19) / 256
                    minimumLuma = min(minimumLuma, luma)
                    maximumLuma = max(maximumLuma, luma)
                    visibleSamples += 1
                }
            }
            return visibleSamples >= 4 && maximumLuma - minimumLuma >= 6
        }
    }
}
