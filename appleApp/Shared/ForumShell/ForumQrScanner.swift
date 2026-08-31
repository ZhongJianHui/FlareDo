@preconcurrency import AVFoundation
import ImageIO
import SwiftUI
import UniformTypeIdentifiers
import Vision

#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

struct ForumQrScanner: View {
    let onCode: (String) -> Void
    let onCancel: () -> Void

    @State private var isImportingImage = false
    @State private var message: LocalizedStringKey?
    @State private var hasCompleted = false

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                ForumQrCameraPreview(
                    onCode: complete,
                    onUnavailable: { message = "forum.qr.camera_unavailable" }
                )
                .ignoresSafeArea(edges: .bottom)

                VStack(spacing: 12) {
                    if let message {
                        Text(message)
                            .font(.callout)
                            .multilineTextAlignment(.center)
                    } else {
                        Text("forum.qr.scan_hint")
                            .font(.callout)
                            .multilineTextAlignment(.center)
                    }
                    Button {
                        isImportingImage = true
                    } label: {
                        Label("forum.qr.choose_image", systemImage: "photo.on.rectangle")
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(16)
                .frame(maxWidth: .infinity)
                .background(.regularMaterial)
            }
            .navigationTitle("forum.qr.scan_title")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("forum.cancel", action: onCancel)
                }
            }
        }
        .fileImporter(isPresented: $isImportingImage, allowedContentTypes: [.image]) { result in
            guard case .success(let url) = result else { return }
            Task {
                do {
                    let value = try await Task.detached { try decodeQrImage(at: url) }.value
                    if let value {
                        complete(value)
                    } else {
                        message = "forum.qr.invalid"
                    }
                } catch {
                    message = "forum.qr.invalid"
                }
            }
        }
    }

    private func complete(_ value: String) {
        guard !hasCompleted else { return }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            message = "forum.qr.invalid"
            return
        }
        hasCompleted = true
        onCode(trimmed)
    }
}

nonisolated private protocol ForumQrCaptureDelegate: AnyObject {
    func qrCaptureDidFind(_ value: String)
    func qrCaptureBecameUnavailable()
}

nonisolated private final class ForumQrCapture: NSObject,
    AVCaptureMetadataOutputObjectsDelegate,
    @unchecked Sendable {
    weak var delegate: ForumQrCaptureDelegate?
    let session = AVCaptureSession()
    let previewLayer: AVCaptureVideoPreviewLayer
    private let sessionQueue = DispatchQueue(label: "io.github.zhongjianhui.flaredo.qr-camera")
    private var configured = false
    private var completed = false

    override init() {
        previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        super.init()
    }

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndStart()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                if granted {
                    self?.configureAndStart()
                } else {
                    self?.reportUnavailable()
                }
            }
        default:
            reportUnavailable()
        }
    }

    func stop() {
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private func configureAndStart() {
        sessionQueue.async { [weak self] in
            guard let self, !completed else { return }
            if !configured {
                guard let camera = AVCaptureDevice.default(for: .video),
                      let input = try? AVCaptureDeviceInput(device: camera),
                      session.canAddInput(input) else {
                    reportUnavailable()
                    return
                }
                let output = AVCaptureMetadataOutput()
                guard session.canAddOutput(output) else {
                    reportUnavailable()
                    return
                }
                session.beginConfiguration()
                session.addInput(input)
                session.addOutput(output)
                output.setMetadataObjectsDelegate(self, queue: .main)
                output.metadataObjectTypes = [.qr]
                session.commitConfiguration()
                configured = true
            }
            if !session.isRunning { session.startRunning() }
        }
    }

    private func reportUnavailable() {
        Task { @MainActor [weak self] in self?.delegate?.qrCaptureBecameUnavailable() }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !completed,
              let value = metadataObjects
                .compactMap({ $0 as? AVMetadataMachineReadableCodeObject })
                .first(where: { $0.type == .qr })?
                .stringValue else { return }
        completed = true
        stop()
        delegate?.qrCaptureDidFind(value)
    }
}

#if os(iOS)
private struct ForumQrCameraPreview: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    let onUnavailable: () -> Void

    func makeUIViewController(context: Context) -> ForumQrCameraViewController {
        ForumQrCameraViewController(onCode: onCode, onUnavailable: onUnavailable)
    }

    func updateUIViewController(_ controller: ForumQrCameraViewController, context: Context) {}
}

private final class ForumQrCameraViewController: UIViewController, ForumQrCaptureDelegate {
    private let capture = ForumQrCapture()
    private let onCode: (String) -> Void
    private let onUnavailable: () -> Void

    init(onCode: @escaping (String) -> Void, onUnavailable: @escaping () -> Void) {
        self.onCode = onCode
        self.onUnavailable = onUnavailable
        super.init(nibName: nil, bundle: nil)
        capture.delegate = self
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.layer.addSublayer(capture.previewLayer)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        capture.previewLayer.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        capture.start()
    }

    override func viewDidDisappear(_ animated: Bool) {
        capture.stop()
        super.viewDidDisappear(animated)
    }

    func qrCaptureDidFind(_ value: String) { onCode(value) }
    func qrCaptureBecameUnavailable() { onUnavailable() }
}
#elseif os(macOS)
private struct ForumQrCameraPreview: NSViewControllerRepresentable {
    let onCode: (String) -> Void
    let onUnavailable: () -> Void

    func makeNSViewController(context: Context) -> ForumQrCameraViewController {
        ForumQrCameraViewController(onCode: onCode, onUnavailable: onUnavailable)
    }

    func updateNSViewController(_ controller: ForumQrCameraViewController, context: Context) {}
}

private final class ForumQrCameraViewController: NSViewController, ForumQrCaptureDelegate {
    private let capture = ForumQrCapture()
    private let onCode: (String) -> Void
    private let onUnavailable: () -> Void

    init(onCode: @escaping (String) -> Void, onUnavailable: @escaping () -> Void) {
        self.onCode = onCode
        self.onUnavailable = onUnavailable
        super.init(nibName: nil, bundle: nil)
        capture.delegate = self
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

    override func loadView() {
        view = NSView()
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor.black.cgColor
        view.layer?.addSublayer(capture.previewLayer)
    }

    override func viewDidLayout() {
        super.viewDidLayout()
        capture.previewLayer.frame = view.bounds
    }

    override func viewWillAppear() {
        super.viewWillAppear()
        capture.start()
    }

    override func viewDidDisappear() {
        capture.stop()
        super.viewDidDisappear()
    }

    func qrCaptureDidFind(_ value: String) { onCode(value) }
    func qrCaptureBecameUnavailable() { onUnavailable() }
}
#endif

nonisolated private func decodeQrImage(at url: URL) throws -> String? {
    let hasScope = url.startAccessingSecurityScopedResource()
    defer { if hasScope { url.stopAccessingSecurityScopedResource() } }
    let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
    guard values.isRegularFile == true,
          let size = values.fileSize,
          size > 0,
          size <= 32 * 1_024 * 1_024 else { return nil }
    let data = try Data(contentsOf: url, options: [.mappedIfSafe])
    guard data.count <= 32 * 1_024 * 1_024,
          let source = CGImageSourceCreateWithData(data as CFData, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
    let request = VNDetectBarcodesRequest()
    request.symbologies = [.qr]
    try VNImageRequestHandler(cgImage: image).perform([request])
    return request.results?.first(where: { $0.symbology == .qr })?.payloadStringValue
}
