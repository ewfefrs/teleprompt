# =============================================================================
# R8 / ProGuard — защита от реверс-инжиниринга (release).
# Обфусцируем и урезаем код, сохраняя то, что ломается без keep-правил.
# =============================================================================

# Убрать все логи в релизе (меньше подсказок в декомпиляции + чуть быстрее).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Агрессивнее переименовывать (в т.ч. пакеты) — сложнее читать декомпиляцию.
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 5

# --- Наши данные, сериализуемые через org.json (доступ по именам полей) ---
# Поля читаются рефлексией JSON только по строкам-ключам в коде, но на всякий
# случай сохраняем сами data-классы модели.
-keep class com.example.teleprompter.SavedText { *; }

# --- Google Play Billing ---
-keep class com.android.billingclient.** { *; }
-dontwarn com.android.billingclient.**

# --- Compose (обычно покрыт дефолтными правилами AGP, дублируем безопасно) ---
-keep class androidx.compose.runtime.** { *; }
-dontwarn kotlinx.coroutines.**

# Сохранять аннотации/сигнатуры дженериков — иначе часть рефлексии Kotlin падает.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
