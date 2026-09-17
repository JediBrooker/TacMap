import SwiftUI
import UIKit
import LinkPresentation

/// SwiftUI wrapper for `UIActivityViewController`. Just bridges the system share
/// sheet so an export can hand off a file URL with a truthful preview title.
struct ShareSheetView: UIViewControllerRepresentable {
    let activityItems: [Any]
    var applicationActivities: [UIActivity]? = nil
    var title: String? = nil

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let presentedItems = activityItems.map { item -> Any in
            guard let title, let url = item as? URL, url.isFileURL else { return item }
            return TitledFileActivityItem(url: url, title: title)
        }
        let controller = UIActivityViewController(activityItems: presentedItems,
                                                  applicationActivities: applicationActivities)
        let urls = activityItems.compactMap { $0 as? URL }.filter(\.isFileURL)
        controller.completionWithItemsHandler = { _, _, _, _ in
            urls.forEach { ExportFileSecurity.remove($0) }
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

private final class TitledFileActivityItem: NSObject, UIActivityItemSource {
    let url: URL
    let title: String

    init(url: URL, title: String) {
        self.url = url
        self.title = title
    }

    func activityViewControllerPlaceholderItem(
        _ activityViewController: UIActivityViewController
    ) -> Any {
        url
    }

    func activityViewController(
        _ activityViewController: UIActivityViewController,
        itemForActivityType activityType: UIActivity.ActivityType?
    ) -> Any? {
        url
    }

    func activityViewController(
        _ activityViewController: UIActivityViewController,
        subjectForActivityType activityType: UIActivity.ActivityType?
    ) -> String {
        title
    }

    func activityViewControllerLinkMetadata(
        _ activityViewController: UIActivityViewController
    ) -> LPLinkMetadata? {
        let metadata = LPLinkMetadata()
        metadata.title = title
        metadata.originalURL = url
        metadata.url = url
        return metadata
    }
}
