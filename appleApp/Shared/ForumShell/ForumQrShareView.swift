import CoreImage.CIFilterBuiltins
import SwiftUI

#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

struct ForumQrShareView: View {
    let share: ForumQrShare
    let isBusy: Bool
    let onRegenerate: () -> Void
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let remaining = max(0, Int(share.expiresAt.timeIntervalSince(context.date)))
                ScrollView {
                    VStack(spacing: 18) {
                        Label("forum.qr.share_warning", systemImage: "exclamationmark.shield")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)

                        if let image = qrImage(for: share.encodedValue) {
                            image
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 260, height: 260)
                                .padding(18)
                                .background(Color.white)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                                .accessibilityLabel(Text("forum.qr.share_code"))
                        } else {
                            ContentUnavailableView(
                                "forum.qr.generate_failed",
                                systemImage: "qrcode"
                            )
                            .frame(height: 296)
                        }

                        if !share.username.isEmpty {
                            Label("@\(share.username)", systemImage: "person")
                        }
                        Text(remaining > 0 ? duration(remaining) : String(localized: "forum.qr.expired"))
                            .font(.title3.monospacedDigit().weight(.semibold))

                        Button(action: onRegenerate) {
                            if isBusy {
                                ProgressView()
                            } else {
                                Label("forum.qr.regenerate", systemImage: "arrow.clockwise")
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(isBusy)
                    }
                    .padding(24)
                    .frame(maxWidth: .infinity)
                }
            }
            .navigationTitle("forum.qr.share_title")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("forum.done", action: onClose)
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, idealWidth: 460, minHeight: 540, idealHeight: 620)
        #endif
    }

    private func duration(_ seconds: Int) -> String {
        let minutes = seconds / 60
        let remainder = seconds % 60
        return String(format: "%02d:%02d", minutes, remainder)
    }

    private func qrImage(for value: String) -> Image? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(value.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        #if os(iOS)
        return Image(uiImage: UIImage(cgImage: cgImage))
        #elseif os(macOS)
        return Image(nsImage: NSImage(cgImage: cgImage, size: .zero))
        #endif
    }
}
