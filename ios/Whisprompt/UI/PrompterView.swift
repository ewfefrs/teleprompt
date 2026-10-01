import SwiftUI

struct PrompterView: View {
    @ObservedObject var model = TeleprompterModel.shared
    @ObservedObject var ble = BleManager.shared
    @State private var selectedId: UUID?
    @State private var scrubTop = 0

    private let green = Color(red: 0.33, green: 0.90, blue: 0.48)

    private var selected: SavedText? {
        if let id = selectedId, let t = model.texts.first(where: { $0.id == id }) { return t }
        return model.texts.first
    }
    private var colChars: Int { Int(model.colWidth) }
    private var localLines: [String] {
        EvenG2Protocol.wrapLines(selected?.body ?? "", lineByteLimit: colChars)
    }
    private var usingGlasses: Bool { ble.isReady && !ble.lines.isEmpty }
    private var displayLines: [String] { usingGlasses ? ble.lines : localLines }
    private var displayTop: Int { usingGlasses ? ble.top : scrubTop }
    private var rows: Int { model.narrow ? EvenG2Protocol.EH_NARROW_ROWS : EvenG2Protocol.EH_WIDE_ROWS }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 16) {
                    connectionCard
                    if model.texts.isEmpty {
                        emptyHint
                    } else {
                        textPicker
                        GlassesPreview(lines: displayLines, top: displayTop, rows: rows)
                        transport
                        sliders
                    }
                }
                .padding()
            }
            .navigationTitle("Prompter")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
    }

    // MARK: подключение

    private var connectionCard: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(ble.isReady ? Color.green : (ble.isScanning ? Color.orange : Color.gray))
                .frame(width: 10, height: 10)
            Text(ble.status).font(.subheadline).foregroundColor(.secondary)
            Spacer()
            Button(ble.isScanning || ble.isReady ? "Disconnect" : "Connect") {
                if ble.isScanning || ble.isReady { ble.disconnect() } else { ble.connect() }
            }
            .buttonStyle(.bordered)
        }
        .padding()
        .background(Color.secondary.opacity(0.1))
        .cornerRadius(12)
    }

    private var emptyHint: some View {
        VStack(spacing: 8) {
            Image(systemName: "doc.text").font(.largeTitle).foregroundColor(.secondary)
            Text("No scripts yet").font(.headline)
            Text("Add a text in the Texts tab, then pick it here.")
                .font(.footnote).foregroundColor(.secondary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 30)
    }

    private var textPicker: some View {
        Menu {
            ForEach(model.texts) { t in
                Button(t.title.isEmpty ? "Untitled" : t.title) { selectedId = t.id; scrubTop = 0 }
            }
        } label: {
            HStack {
                Image(systemName: "doc.text")
                Text(selected?.title.isEmpty == false ? selected!.title : "Untitled")
                    .lineLimit(1)
                Spacer()
                Image(systemName: "chevron.up.chevron.down").font(.caption)
            }
            .padding()
            .background(Color.secondary.opacity(0.1))
            .cornerRadius(12)
        }
    }

    // MARK: транспорт

    private var transport: some View {
        HStack(spacing: 14) {
            stepButton("arrow.up") { scrollBack() }
            Button {
                togglePlay()
            } label: {
                Image(systemName: model.playing ? "pause.fill" : "play.fill")
                    .font(.title2)
                    .frame(width: 64, height: 44)
            }
            .buttonStyle(.borderedProminent)
            .disabled(selected == nil || !ble.isReady)

            Button { model.stop() } label: {
                Image(systemName: "stop.fill").font(.title3).frame(width: 44, height: 44)
            }
            .buttonStyle(.bordered)

            stepButton("arrow.down") { scrollFwd() }
        }
        .frame(maxWidth: .infinity)
    }

    private func stepButton(_ icon: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.title3).frame(width: 44, height: 44)
        }
        .buttonStyle(.bordered)
    }

    private func togglePlay() {
        if model.playing { model.pause() }
        else if usingGlasses { model.resume() }
        else if let t = selected { model.play(t) }
    }
    private func scrollBack() {
        if usingGlasses { model.scrollBy(-1) } else { scrubTop = max(0, scrubTop - 1) }
    }
    private func scrollFwd() {
        if usingGlasses { model.scrollBy(1) }
        else { scrubTop = min(max(0, localLines.count - rows), scrubTop + 1) }
    }

    // MARK: ползунки

    private var sliders: some View {
        VStack(spacing: 14) {
            sliderRow("Speed", value: $model.wpm, range: 60...250, unit: "wpm") {
                model.saveSettings(); model.applySpeedIfPlaying()
            }
            sliderRow("Width", value: $model.colWidth, range: 20...44, unit: "ch") {
                model.saveSettings()
            }
            sliderRow("Brightness", value: $model.brightness, range: 2...100, unit: "") {
                model.applyBrightness()
            }
            Toggle(isOn: $model.narrow) {
                Text("Narrow screen")
            }
            .onChange(of: model.narrow) { _ in model.applyNarrow() }
            .tint(green)
        }
        .padding()
        .background(Color.secondary.opacity(0.1))
        .cornerRadius(12)
    }

    private func sliderRow(_ title: String, value: Binding<Double>, range: ClosedRange<Double>,
                           unit: String, onChange: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title).font(.subheadline)
                Spacer()
                Text("\(Int(value.wrappedValue))\(unit.isEmpty ? "" : " \(unit)")")
                    .font(.caption).foregroundColor(.secondary)
            }
            Slider(value: value, in: range)
                .tint(green)
                .onChange(of: value.wrappedValue) { _ in onChange() }
        }
    }
}
