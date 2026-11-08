# Подпись release APK

Для обновления поверх предыдущей версии Android требует неизменные applicationId и сертификат подписи, а также увеличение versionCode. В проекте используется applicationId `com.homebabymonitor`.

Приватный ключ **не должен находиться в публичном репозитории, исходниках, логах CI или открытых артефактах**. Храните резервную копию keystore отдельно от пароля. Потеря ключа лишит возможности выпускать обычные обновления для уже установленного APK.

## Локальная подпись

1. Соберите неподписанный APK: `gradle :app:assembleRelease`.
2. Выровняйте APK: `zipalign -p -f 4 app-release-unsigned.apk aligned.apk`.
3. Подпишите одним и тем же ключом: `apksigner sign --ks /path/to/private-release.jks --ks-key-alias home-baby-monitor --out HomeBabyMonitor-release.apk aligned.apk`.
4. Проверьте: `apksigner verify --verbose --print-certs HomeBabyMonitor-release.apk`.

Выполняйте подписывание локально. В CI формируется только неподписанный APK; автоматически публиковать release с новым ключом при каждом запуске **запрещено**.

## Первое обновление с debug

Debug-сборки и подписанные release-сборки имеют разные сертификаты. Перед установкой первой release-сборки удалите ранее установленную debug-версию. Последующие release-сборки, подписанные **тем же ключом**, должны устанавливаться как обновление без удаления данных.
