# NotCode для Android

Мобильная IDE с ИИ-агентом: проект открывается через SAF, файлы остаются у
пользователя, агент работает в рабочей копии и запрашивает подтверждение на
каждое действие. Это основной исходник Android-приложения (`com.notcode.mobile`,
Compose UI, Kotlin).

## Сборка

Нужен JDK 17 и Android SDK. Локальный путь к SDK задаётся в `local.properties`
(`sdk.dir=../.tools/android-sdk` в этом checkout), в CI он поднимается через
переменные окружения.

```powershell
.\gradlew.bat :app:assembleDebug      # debug-APK (splits: arm64-v8a, armeabi-v7a + universal)
.\gradlew.bat :app:testDebugUnitTest  # unit-тесты
.\gradlew.bat :app:lintDebug          # lint
```

Полный локальный прогон (сборка + lint + unit-тесты через изолированный JUnit
раннер, обходящий ограничения песочницы Windows):

```powershell
cmd /c build-local.cmd
```

Release-сборка подписывается материалом из `../.keys/notcode-release.properties`
(вне дерева исходников, не публикуется); без него release собирается
неподписанным. В CI ключи берутся из секретов репозитория
(`NOTCODE_RELEASE_PROPERTIES`, `NOTCODE_RELEASE_KEYSTORE_B64` в workflow
`.github/workflows/android.yml`).

`targetSdk` намеренно равен 28: PRoot исполняет бинарники только для legacy-домена
`untrusted_app_25/27` (подробности — раздел 7 `docs/ARCHITECTURE.md`).

## Структура

- `app/src/main/java/com/notcode/mobile/` — приложение по слоям:
  - `agent/`, `llm/`, `network/` — цикл агента, провайдеры, веб-инструменты;
  - `chat/`, `ui/` — чат и Compose-интерфейс; таймлайн агента: действия,
    индикаторы и сообщения модели — отдельные записи;
  - `filesystem/` — SAF и внутренняя рабочая копия;
  - `git/` — JGit и системный git из Ubuntu-окружения;
  - `runtime/`, `terminal/`, `ssh/` — PRoot-окружение и PTY-терминал;
  - `desktop/` — сопряжение Desktop↔Mobile (TLS-pinning, Bearer, превью-релей);
  - `auth/`, `security/` — профили провайдера, хранилище секретов, границы модели;
  - `update/` — проверка релизов GitHub и установка APK.
- `terminal-emulator/`, `terminal-view/` — библиотеки терминала.
- `app/src/test/` — unit-тесты (22 файла: агент, SAF-пути, git, десктоп-сопряжение,
  безопасный tar, релизы); `app/src/androidTest/` — инструментальные тесты.

## Границы продукта

- ключи разработчика и сервер-посредник не используются; профиль и API-ключ
  задаёт пользователь;
- команда исполняется только в opt-in Linux/PRoot-окружении, только в рабочей
  копии и только после подтверждения;
- запись файла показывает diff, команда — полный текст, каталог и изменения;
- модель не видит API-ключ, настройки провайдера и не делает произвольный HTTP;
- пользовательские HTML/JS не открываются как страницы приложения.
