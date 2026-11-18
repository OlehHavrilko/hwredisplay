# HardwareDisplay

Android-телефон как второй экран датчиков Windows-ПК. Windows-приложение читает телеметрию, поднимает TLS-сервер в локальной сети и отдаёт её по WebSocket; телефон собирает из показателей собственную панель и остаётся на виду во время игры или рендера.

Состояние: работают Windows-хост и Android-клиент. Хост читает CPU, RAM, GPU и VRAM, диски, температуры и вентиляторы, держит TLS-сервер, сопряжение и поток `hello → catalog → snapshot`, живёт в трее без окна (окно настроек и QR открывается по требованию), с автозапуском и правилом брандмауэра. Телефон сопрягается по QR и вручную, показывает панель, пресеты, редактор, сравнение и локальную историю. Сквозной сценарий проверен на эмуляторе Android 17 (профиль Poco X5 Pro 5G) с реальным ПК.

| Что | Где |
| --- | --- |
| Документация проекта | [docs/README.md](docs/README.md) |
| Продуктовые цели и сценарий | [MAIN_PROMPT.md](MAIN_PROMPT.md) |
| Архитектура | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Протокол v1 | [docs/PROTOCOL.md](docs/PROTOCOL.md) |
| Дизайн панели | [docs/DESIGN.md](docs/DESIGN.md) |
| Android-клиент | [docs/ANDROID_CLIENT.md](docs/ANDROID_CLIENT.md) |
| Хост: датчики, трей, оптимизация | [docs/HOST_DESKTOP.md](docs/HOST_DESKTOP.md) |
| План, критерии и текущее состояние | [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) |
| Обоснование выбора источников датчиков | [reports/Мониторинг ПК с телефона.md](reports/Мониторинг%20ПК%20с%20телефона.md) |

## Сборка и запуск

```powershell
. .\scripts\dev-env.ps1
dotnet build HardwareDisplay.slnx
dotnet test windows\tests\HardwareDisplay.Tests
windows\HardwareDisplay.Host\bin\Debug\net10.0-windows\HardwareDisplay.Host.exe
```

Android-клиент собирается отдельным Gradle-проектом в `android/` и открывается в Android Studio как этот каталог:

```powershell
.\android\gradlew.bat -p android :app:assembleDebug
.\android\gradlew.bat -p android :app:testDebugUnitTest
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

Нужен .NET SDK 10.0.404 (закреплён `global.json`) и JDK 17+ для Android (использован JBR из Android Studio). `scripts\dev-env.ps1` заполняет переменные окружения, без которых NuGet на некоторых машинах падает с `Value cannot be null. (Parameter 'path1')`.

После первого запуска Windows спросит про доступ в сеть — без разрешения телефон не подключится. Адрес, имя ПК, отпечаток сертификата и код приглашения показывает окно хоста.

## Структура

```text
windows/HardwareDisplay.Core      Каталог датчиков, расписание опроса, состояния, снимки
windows/HardwareDisplay.Windows   Датчики: Win32, PDH (GPU, диски), DXGI, LibreHardwareMonitor
windows/HardwareDisplay.Host/Desktop  Трей, автозапуск, правило брандмауэра
windows/HardwareDisplay.Host      WPF-окно, идентичность, Kestrel, сопряжение, WebSocket
windows/tests                     Тесты сбора, идентичности, TLS и рассылки
android/app                       Kotlin/Compose-клиент: протокол, pinned TLS, сессия, панель, Room
scripts/                          Окружение сборки и проверки интерфейса
tools/protocol-smoke.py           Сторонний клиент протокола для проверки живого сервера
docs/ reports/ research_notes/    Проект, исследование и доказательства
```
