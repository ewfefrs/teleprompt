import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Лёгкая защита от реверс-инжиниринга / подмены (аналог Android IntegrityGuard).
/// Проверяет на запуске (только в RELEASE):
///  1. к процессу НЕ подключён отладчик (sysctl P_TRACED);
///  2. устройство НЕ джейлбрейкнуто (типовые пути / запись вне песочницы / cydia://);
///  3. bundle identifier совпадает с ожидаемым (защита от переупаковки).
///
/// При обнаружении вмешательства выставляется флаг Prefs.KEY_TAMPERED — премиум-функции
/// тогда отключаются (PremiumManager.isPremium вернёт false). Приложение НЕ падает: мягкая
/// деградация лучше ложного краша. В DEBUG проверки пропускаются.
enum IntegrityGuard {

    private static let EXPECTED_BUNDLE_ID = "com.whisprompt.app"

    static func check() {
        #if DEBUG
        return
        #else
        var tampered = false
        if isDebuggerAttached() { tampered = true }
        if isJailbroken() { tampered = true }
        if Bundle.main.bundleIdentifier != EXPECTED_BUNDLE_ID { tampered = true }
        Prefs.putBool(Prefs.KEY_TAMPERED, tampered)
        #endif
    }

    /// Отладчик, подключённый к процессу (флаг P_TRACED в kinfo_proc).
    static func isDebuggerAttached() -> Bool {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        let result = mib.withUnsafeMutableBufferPointer { ptr -> Int32 in
            sysctl(ptr.baseAddress, u_int(ptr.count), &info, &size, nil, 0)
        }
        guard result == 0 else { return false }
        return (info.kp_proc.p_flag & P_TRACED) != 0
    }

    /// Признаки джейлбрейка. На симуляторе всегда false.
    static func isJailbroken() -> Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        let suspiciousPaths = [
            "/Applications/Cydia.app",
            "/Applications/Sileo.app",
            "/Library/MobileSubstrate/MobileSubstrate.dylib",
            "/usr/sbin/sshd",
            "/usr/bin/ssh",
            "/bin/bash",
            "/etc/apt",
            "/private/var/lib/apt/",
            "/private/var/lib/cydia",
        ]
        for path in suspiciousPaths where FileManager.default.fileExists(atPath: path) {
            return true
        }
        // Запись вне песочницы возможна только на джейле.
        let probe = "/private/whisprompt_probe.txt"
        if (try? "x".write(toFile: probe, atomically: true, encoding: .utf8)) != nil {
            try? FileManager.default.removeItem(atPath: probe)
            return true
        }
        #if canImport(UIKit)
        if let url = URL(string: "cydia://package/com.example.package"),
           UIApplication.shared.canOpenURL(url) {
            return true
        }
        #endif
        return false
        #endif
    }
}
