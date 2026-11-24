import WidgetKit
import SwiftUI

@main
struct MetrolistRecognitionWidgetBundle: WidgetBundle {
    var body: some Widget {
        if #available(iOS 18.0, *) {
            RecognitionControl()
        }
    }
}
