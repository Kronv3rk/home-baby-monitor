# Подпись release APK

Постоянная подпись применяется с **0.4.2** (`versionCode = 8`, `applicationId = com.homebabymonitor`).

Отпечаток сертификата подписи SHA-256:

`f0fcd91e6307440ec04200480fb3f48491627e4fd3f70fa93069037274c43a9c`

Последующие APK должны иметь **точно такой же отпечаток**, иначе Android не установит обновление поверх ранее установленного релиза. Версия 0.4.5 подписана тем же ключом и проверена на совпадение сертификата с 0.4.4; автоматическая публикация подписанных APK из GitHub Actions пока не настроена, так как приватный ключ не хранится в публичном репозитории.

Для обновления поверх предыдущей версии Android требует неизменные applicationId и сертификат подписи, а также увеличение versionCode. В проекте используется applicationId `com.homebabymonitor`.

Приватный ключ **не должен находиться в публичном репозитории, исходниках, логах CI или открытых артефактах**. Храните резервную копию keystore отдельно от пароля. Потеря ключа лишит возможности выпускать обычные обновления для уже установленного APK.

## Подписанный APK в GitHub Releases

В [Publish signed APK](.github/workflows/publish-release.yml) настроена сборка и загрузка **подписанного** файла в Releases. Нужны два приватных GitHub Actions Secrets: `HBM_RELEASE_KEYSTORE_BASE64` (base64 существующего PKCS12-ключа) и `HBM_RELEASE_KEYSTORE_PASSWORD`. Значения не должны попадать в git, workflow YAML, комментарии или логи. Текущее подключение GitHub не умеет записывать Secrets; владелец репозитория должен один раз добавить их в Settings → Secrets and variables → Actions. Поэтому **0.4.4 отсутствует в GitHub Releases**, хотя подписанный APK был передан в чате; публиковать неподписанный APK вместо него нельзя. После настройки этих двух секретов workflow выпускает подписанный APK для текущей версии. Workflow проверяет соответствие сертификата постоянному отпечатку 0.4.2. Перед добавлением секретов необходимо защитить ветку `main` от непроверенных изменений и, по возможности, назначить для выпуска GitHub Environment с подтверждением запуска. Иначе любой участник с правом изменять workflow сможет попытаться раскрыть секреты подписи.

## Локальная подпись

1. Соберите неподписанный APK: `gradle :app:assembleRelease`.
2. Выровняйте APK, включая нативные библиотеки: `zipalign -P 16 -f 4 app-release-unsigned.apk aligned.apk`.
3. Подпишите одним и тем же ключом: `apksigner sign --alignment-preserved true --ks /path/to/private-release.p12 --ks-type PKCS12 --ks-key-alias home-baby-monitor --out HomeBabyMonitor-release.apk aligned.apk`.
4. Проверьте: `apksigner verify --verbose --print-certs HomeBabyMonitor-release.apk`.

Выполняйте подписывание локально. В CI формируется только неподписанный APK; автоматически публиковать release с новым ключом при каждом запуске **запрещено**.

## Первое обновление с debug

Debug-сборки и подписанные release-сборки имеют разные сертификаты. Перед установкой первой release-сборки удалите ранее установленную debug-версию. Последующие release-сборки, подписанные **тем же ключом**, должны устанавливаться как обновление без удаления данных.

## Последний подписанный APK

- Версия: **0.4.5** (`versionCode = 11`).
- Подписан тем же сертификатом, что и 0.4.2.
- SHA-256: `e8d33412871ac15a11e24a46878269c936e9fb64473d39146e825cc0af32ee9b`.
- Выпуск подписанных APK на GitHub Releases не автоматизирован: в CI нет приватного ключа. Не подменять подписанный APK неподписанным CI-артефактом.

## Защита репозитория перед сохранением ключа в GitHub

Сначала откройте [Rulesets](https://github.com/Kronv3rk/home-baby-monitor/settings/rules) и создайте активный **branch ruleset** для `main`. Включите:

- Require a pull request before merging (0 approvals при одном сопровождающем; 1 или больше при наличии независимого ревьюера).
- Require status checks to pass: **Tests, lint, release package**.
- Block force pushes и Restrict deletions; не добавляйте администраторов в список обхода правил без необходимости.

Workflow `Android CI` запускается на **каждом PR в main**, даже если изменена только документация. Иначе обязательная проверка зависла бы в состоянии Pending.

Далее откройте [Environments](https://github.com/Kronv3rk/home-baby-monitor/settings/environments), создайте окружение **release-signing** и в разделе **Deployment branches and tags** разрешите только выбранную ветку `main`. В **Environment secrets**, а не в Repository secrets, добавьте существующие:

- `HBM_RELEASE_KEYSTORE_BASE64` — содержимое файла Base64 ключа.
- `HBM_RELEASE_KEYSTORE_PASSWORD` — пароль PKCS12.

Если ранее секреты добавлены на уровне Repository, перенесите их в Environment secrets и удалите repository-копии. Текущий release workflow использует окружение `release-signing` и не запускает подпись, пока на `main` не появятся активные правила для pull requests и обязательного CI.

**Предупреждение:** правило для `main` и проверка в workflow не защищают от владельца аккаунта и участников, которым явно разрешён bypass. Храните ключ офлайн, используйте двухфакторную аутентификацию GitHub и по возможности проверяйте изменения workflow отдельным человеком.
