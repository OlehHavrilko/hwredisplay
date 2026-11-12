# HardwareDisplay

Android-телефон как второй экран датчиков Windows-ПК. Windows-приложение читает телеметрию, поднимает TLS-сервер в локальной сети и отдаёт её по WebSocket; телефон собирает из показателей собственную панель и остаётся на виду во время игры или рендера.

Состояние: собран Windows-хост первого этапа — реальные CPU и RAM, идентичность ПК с самоподписанным сертификатом, сопряжение по QR, поток `hello → catalog → snapshot`. Android-клиент и датчики GPU, дисков и температур — следующие этапы.

| Что | Где |
| --- | --- |
| Документация проекта | [docs/README.md](docs/README.md) |
| Продуктовые цели и сценарий | [MAIN_PROMPT.md](MAIN_PROMPT.md) |
| Архитектура | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Протокол v1 | [docs/PROTOCOL.md](docs/PROTOCOL.md) |
| Дизайн панели | [docs/DESIGN.md](docs/DESIGN.md) |
| План, критерии и текущее состояние | [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) |
| Обоснование выбора источников датчиков | [reports/Мониторинг ПК с телефона.md](reports/Мониторинг%20ПК%20с%20телефона.md) |

## Сборка и запуск

```powershell
. .\scripts\dev-env.ps1
dotnet build HardwareDisplay.slnx
dotnet test windows\tests\HardwareDisplay.Tests
windows\HardwareDisplay.Host\bin\Debug\net10.0-windows\HardwareDisplay.Host.exe
```

Нужен .NET SDK 10.0.404 (закреплён `global.json`). `scripts\dev-env.ps1` заполняет переменные окружения, без которых NuGet на некоторых машинах падает с `Value cannot be null. (Parameter 'path1')`.

После первого запуска Windows спросит про доступ в сеть — без разрешения телефон не подключится. Адрес, имя ПК, отпечаток сертификата и код приглашения показывает окно хоста.

## Структура

```text
windows/HardwareDisplay.Core      Каталог датчиков, расписание опроса, состояния, снимки
windows/HardwareDisplay.Windows   Win32-провайдеры: GetSystemTimes, GlobalMemoryStatusEx
windows/HardwareDisplay.Host      WPF-окно, идентичность, Kestrel, сопряжение, WebSocket
windows/tests                     Тесты сбора, идентичности, TLS и рассылки
scripts/                          Окружение сборки и проверки интерфейса
tools/protocol-smoke.py           Сторонний клиент протокола для проверки живого сервера
docs/ reports/ research_notes/    Проект, исследование и доказательства
```
