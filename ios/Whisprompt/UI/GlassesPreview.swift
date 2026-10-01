import SwiftUI

/// Превью «как на очках»: показывает окно из `rows` строк начиная с `top`, плюс полоса прогресса.
struct GlassesPreview: View {
    let lines: [String]
    let top: Int
    let rows: Int

    private let green = Color(red: 0.33, green: 0.90, blue: 0.48)

    private var window: [String] {
        guard !lines.isEmpty else { return Array(repeating: " ", count: rows) }
        let start = min(max(top, 0), max(0, lines.count - 1))
        var w = [String]()
        var i = start
        while i < lines.count && w.count < rows { w.append(lines[i]); i += 1 }
        while w.count < rows { w.append(" ") }
        return w
    }

    private var progress: Double {
        guard lines.count > rows else { return lines.isEmpty ? 0 : 1 }
        return Double(top) / Double(max(1, lines.count - rows))
    }

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(Array(window.enumerated()), id: \.offset) { _, line in
                    Text(line.isEmpty ? " " : line)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundColor(green)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity, alignment: .center)
                }
            }
            .frame(maxWidth: .infinity)

            // полоса прогресса справа
            GeometryReader { geo in
                ZStack(alignment: .top) {
                    Capsule().fill(green.opacity(0.2))
                    Capsule()
                        .fill(green)
                        .frame(height: max(12, geo.size.height * 0.18))
                        .offset(y: (geo.size.height - max(12, geo.size.height * 0.18)) * progress)
                }
            }
            .frame(width: 5)
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .frame(height: 180)
        .background(Color.black)
        .cornerRadius(14)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(green.opacity(0.25), lineWidth: 1))
    }
}
