// Версии зафиксированы явно (без "+"/диапазонов) и подобраны взаимно совместимыми:
//   Gradle 8.9  ←→  AGP 8.7.3  ←→  Kotlin 2.0.21  ←→  Compose Compiler 2.0.21
//   compileSdk 35 поддерживается начиная с AGP 8.6 без предупреждений.
// Менять версии по отдельности нельзя — только согласованным набором.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
