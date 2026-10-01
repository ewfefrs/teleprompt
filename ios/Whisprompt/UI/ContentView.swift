import SwiftUI

/// Корень приложения: три вкладки — телесуфлёр, тексты, настройки.
struct ContentView: View {
    private let green = Color(red: 0.33, green: 0.90, blue: 0.48)

    var body: some View {
        TabView {
            PrompterView()
                .tabItem { Label("Prompter", systemImage: "text.alignleft") }
            TextsView()
                .tabItem { Label("Texts", systemImage: "doc.text") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .tint(green)
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View { ContentView() }
}
