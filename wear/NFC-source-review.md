# NFC на Wear OS: проверка открытых исходников, 2026-10-16

Проверены исходники AOSP `platform/packages/modules/Nfc`: Android 16 QPR2
(`android-16.0.0_r4`) и текущая ветка `main`. Это не извлечённые бинарники конкретной
прошивки Galaxy Watch9; перенос выводов на Samsung требует проверки устройства.

## Чтение и эмуляция — разные возможности

В `NfcAdapter.enableReaderMode` проверяется `sHasNfcFeature`, при его отсутствии
выбрасывается `UnsupportedOperationException`. При этом некоторые другие операции
допускают NFC либо card-emulation features. Поэтому существование адаптера, HCE или
оплаты на часах не доказывает возможность считать UID внешней карты.
[NfcAdapter, main](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/framework/java/android/nfc/NfcAdapter.java).

Приложение вызывает стандартный reader mode с NFC-A и `FLAG_READER_SKIP_NDEF_CHECK`.
При отказе сохраняет исходную ошибку, не пытается включить скрытый режим чтения или
обойти ограничения прошивки. Начиная с тестовой версии 0.3 UID можно добавить вручную.

## OEM API задания UID

В релизном `NfcOemExtension` имеется `emulateNfcTechnologyATag`, защищённый
`WRITE_SECURE_SETTINGS` и флагом `FLAG_OEM_EXTENSION_25Q4`. Контракт принимает UID
4, 7 или 10 байт. В просмотренном `main` этого метода нет, поэтому SDK или название
Wear OS не заменяют проверку фактического метода в прошивке.
[NfcOemExtension, android-16.0.0_r4](https://android.googlesource.com/platform/packages/modules/Nfc/+/android-16.0.0_r4/framework/java/android/nfc/NfcOemExtension.java).

`NfcService.emulateNfcATag` проверяет административное разрешение, останавливает
discovery, задаёт `0x85`, `0x30`, `0x31`, `0x32`, `0x33`, `0x58`, опционально `0x59`,
затем применяет routing. При отключении API снимает custom config и очищает часть
значений. В этой реализации отдельные результаты `setNciConfig` не передаются
вызывающему приложению: служба в конце возвращает OK. Следовательно, статус OK сам
по себе не доказывает работу контроллера. В часовой сборке добавлено readback UID,
когда доступен raw GET; наблюдаемое несовпадение вызывает откат. Отсутствие readback
оставляет результат неподтверждённым до внешнего считывателя.
[NfcService, android-16.0.0_r4](https://android.googlesource.com/platform/packages/modules/Nfc/+/android-16.0.0_r4/NfcNci/src/com/android/nfc/NfcService.java).

## Raw NCI и управление discovery

`NfcService.sendVendorNciMessage` вызывает `NfcPermissions.enforceAdminPermissions`,
проверяет включённый NFC и передаёт запрос через `DeviceHost.sendRawVendorCmd`.
Административное разрешение — `WRITE_SECURE_SETTINGS`. Это объясняет ADB grant,
но доступность метода и разрешения ещё не гарантирует принятие SET контроллером.
[NfcService, main](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/NfcNci/src/com/android/nfc/NfcService.java),
[NfcPermissions, main](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/NfcNci/src/com/android/nfc/NfcPermissions.java).

`setDiscoveryTechnology` допускает NFC или CE features и несовместим с reader mode.
Часовая сборка завершает чтение перед активацией, а для явно разрешённого raw на
часах предпочитает этот API, сохраняя телефонную политику для остальных устройств.
Общий контроллер возобновляет discovery после операций. На watch используется
точный снимок шести реально изменяемых raw-параметров; прочие состояния secure
element и проприетарной маршрутизации не объявляются полностью сохранёнными.
[NfcAdapter, main](https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/framework/java/android/nfc/NfcAdapter.java).

## Результаты на Watch9

Получен отчёт Samsung SM-L355F (`fresh9ul`, hardware `qcom`), Android 17 / API 37,
fingerprint `samsung/fresh9ulxx/fresh9ul:17/CP2A.260330.023/L355FXXS1AZH4:user/release-keys`,
security patch 2026-08-05. FEATURE_NFC=false, HCE/HCEF/eSE=true; `enableReaderMode`
выбросил `UnsupportedOperationException` в `NfcAdapter.java:1834`. Разрешение
WRITE_SECURE_SETTINGS выдано. OEM UID API и raw NCI API разрешаются; управление
discovery доступно. Ни GET/SET, ни активация внешним считывателем этим отчётом не
подтверждены. Версия 0.3 добавляет ручное сохранение UID для проверки эмуляции.

Дополнение 2026-10-17: пользователь подтвердил работу на внешнем считывателе через
обычный OEM-путь с UID 4 байта. Это подтверждение относится к описанным часам
SM-L355F; отдельного нового отчёта о прошивке и операциях не получено.
Работа с погашенным экраном не проверялась. UID 7/10 байт, raw-записи,
восстановление NFC и другие модели остаются неподтверждёнными.

Публичный исходный код NFC-службы конкретной прошивки Samsung в этой работе
не получен. Скрипт `collect-watch-nfc.py` сохраняет
эти сведения и по явному флагу скачивает доступные NFC APK и framework JAR для
последующего анализа. Он не отправляет NCI-команд и не меняет настройки.

Установка ADB описана в
[официальной инструкции Samsung](https://developer.samsung.com/health/sensor/guide/connect-watch.html).
