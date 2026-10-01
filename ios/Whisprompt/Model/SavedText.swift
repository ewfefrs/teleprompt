import Foundation

/// Сохранённый скрипт телесуфлёра.
struct SavedText: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var title: String
    var body: String

    var wordCount: Int {
        body.split(whereSeparator: { $0.isWhitespace }).count
    }
}
