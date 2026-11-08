# Сведения о стороннем ПО

**Прямые зависимости текущего Android-проекта по `app/build.gradle.kts`.**

Исходный код HomeBabyMonitor лицензируется отдельно по [MIT](LICENSE). Лицензия MIT **не заменяет** лицензии сторонних библиотек и компонентов внутри Android APK.

| Компонент | Версия в проекте | Лицензия и источник |
|---|---|---|
| ZXing Android Embedded (`com.journeyapps:zxing-android-embedded`) | 4.3.0 | Apache-2.0 — [JourneyApps](https://github.com/journeyapps/zxing-android-embedded) |
| ZXing Core | Транзитивная зависимость | Apache-2.0 — [ZXing](https://github.com/zxing/zxing) |
| WebRTC Android SDK (`io.github.webrtc-sdk:android`) | 150.7871.01 | BSD-3-Clause для WebRTC, см. [текст лицензии](licenses/WEBRTC-BSD-3-CLAUSE.txt), [Maven Central](https://central.sonatype.com/artifact/io.github.webrtc-sdk/android) и [проект](https://github.com/webrtc-sdk/android) |
| AndroidX Activity Compose | 1.10.1 | Apache-2.0 — [AndroidX](https://developer.android.com/jetpack/androidx) |
| Jetpack Compose BOM и Compose UI / Material3 / Material Icons Extended | BOM 2025.04.01 | Apache-2.0 — [AndroidX](https://developer.android.com/jetpack/compose) |
| AndroidX Lifecycle Runtime Compose | 2.9.0 | Apache-2.0 — [AndroidX](https://developer.android.com/jetpack/androidx) |
| Kotlin Coroutines Android | 1.10.2 | Apache-2.0 — [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| JUnit | 4.13.2 (только тесты, не APK) | Eclipse Public License 1.0 — [JUnit 4](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) |

Другие транзитивные зависимости, поставляемые внутри AAR/JAR, в том числе кодеки и библиотеки WebRTC, могут иметь отдельные уведомления. При распространении **готового APK** необходимо проверить фактически включённые зависимости и сохранить применимые лицензионные тексты и copyright notices. Эта таблица не утверждает, что полный аудит транзитивных зависимостей уже проведён.

Официальная лицензия WebRTC требует сохранять уведомление об авторских правах, условия и отказ от гарантий как при распространении исходного кода, так и при распространении бинарных сборок. Запрещено использовать имена Google и авторов WebRTC для продвижения производных продуктов без отдельного разрешения.

Публикация APK в Releases сама по себе не предоставляет разработчику прав на чужие товарные знаки, фотографии, логотипы или записи. Не добавляйте такие материалы без разрешения правообладателей.
