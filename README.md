# Market Data Server

REST API сервер для предоставления агрегированных рыночных данных: свечные агрегаты, footprint (профиль объёмов по ценовым уровням) и данные о ликвидациях. Читает данные из PostgreSQL базы `trade_collector`, наполняемой сервисом [Trade Collector](https://github.com/forskillzor/TradeCollectorService).

**Архитектура**: PostgreSQL → AggregateDAO (HikariCP, read-only) → Ktor/Jetty HTTP API → клиенты (дашборды, аналитика, UI).

## Быстрый старт (dev)

```bash
# 1. Поднять PostgreSQL (из trade-collector)
make dev-up

# 2. Собрать и запустить
make build && make run

# 3. Проверить
curl http://localhost:8085/health
```

Переменные окружения (подставляются в `make run`):
```bash
DB_HOST=localhost
DB_PORT=5432
DB_NAME=trade_collector
DB_USER=trade_user
DB_PASSWORD=dev_password
HTTP_HOST=0.0.0.0
HTTP_PORT=8085
```

## Production деплой

```bash
# Собрать пакет и задеплоить на VPS
VPS_HOST=95.81.99.28 VPS_USER=deploy VPS_SSH_KEY=~/.ssh/vps_key make deploy
```

`make deploy` собирает fat JAR, пакует в архив с systemd-юнитом и скриптом запуска, копирует на VPS в `/opt/market-data-server/current/`, устанавливает и перезапускает systemd-сервис, затем делает health-check.

Конфигурация на VPS — файл `/etc/default/market-data-server` (формат `KEY=VALUE`).

## API

Все эндпоинты — `GET`, CORS разрешён для любых хостов.

### Агрегаты (свечи)

| Endpoint | Параметры | Описание |
|---|---|---|
| `/api/aggregates` | `exchange`, `symbol`, `timeframe` (обязательные); `from`, `to`, `limit` (по умолчанию 1000) | Список свечных агрегатов с OHLC, объёмами и сырым footprint JSON |
| `/api/footprint` | `exchange`, `symbol`, `timeframe` (обязательные); `from`, `to`, `limit` (по умолчанию 20) | Footprint-свечи с разбором объёмов по ценовым уровням (bid/ask) |
| `/api/footprint/{symbol}/levels` | `symbol` (в пути); `exchange`, `timeframe`, `startTime`, `endTime` | Детализация ценовых уровней одной свечи |

### Ликвидации

| Endpoint | Параметры | Описание |
|---|---|---|
| `/api/liquidations` | `symbol` (обязательный); `exchange`, `from`, `to`, `limit` (100) | Список отдельных ликвидаций (цена, объём, long/short) |
| `/api/liquidation-aggregates` | `symbol` (обязательный); `exchange`, `timeframe`, `from`, `limit` (100) | Агрегированные long/short ликвидации по таймфреймам |

### Метаданные

| Endpoint | Параметры | Описание |
|---|---|---|
| `/api/instruments` | `exchange` (Binance) | Список доступных инструментов с диапазоном данных |
| `/api/symbols` | `exchange` (Binance) | Список символов |
| `/api/timeframes` | `exchange` (Binance), `symbol` (обязательный) | Список доступных таймфреймов для символа |

### Health

| Endpoint | Описание |
|---|---|
| `/health` | `{"status":"healthy"/"degraded","service":"market-data-server"}` — проверка БД через `SELECT 1` |

## Структура проекта

```
market-data-server/
├── src/main/kotlin/com/marketdata/
│   ├── Main.kt                   # Точка входа: конфигурация, запуск, shutdown hook
│   ├── ApiServer.kt              # Ktor/Jetty HTTP сервер: роуты, CORS, модели данных
│   ├── AggregateDAO.kt           # Доступ к БД: HikariCP пул, запросы, кэш символов
│   └── AggregatesQuery.kt        # Data class для параметров запроса
├── src/main/resources/
│   └── logback.xml               # Конфигурация логирования
├── build.gradle.kts              # Gradle: Ktor, kotlinx.serialization, HikariCP, PostgreSQL driver
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/
├── Makefile                      # build, run, package, deploy
└── .gitignore
```

## База данных

Сервер читает из PostgreSQL базы `trade_collector`. Таблицы — динамические, по одной на символ:

- `aggregates_{symbol}` — свечные агрегаты (start_time, end_time, total_ticks, min_price, max_price, price_levels, price_levels_jsonb)
- `liquidations_{symbol}` — отдельные ликвидации (timestamp, price, quantity, is_long, order_type)
- `liquidation_aggregates_{symbol}` — агрегированные ликвидации по таймфреймам (start_time/end_time, long_count/volume, short_count/volume)

Символы обнаруживаются через `pg_catalog.pg_tables` (все таблицы `aggregates_%`), результат кэшируется на 30 секунд.

## Технический стек

| Компонент | Технология |
|---|---|
| Язык | Kotlin (JDK 21) |
| HTTP-сервер | Ktor 3.2.0 на Jetty |
| JSON | kotlinx.serialization |
| Connection pool | HikariCP 6.0.0 (max 10) |
| База данных | PostgreSQL, драйвер 42.7.1 |
| Логирование | kotlin-logging + Logback |
| Сборка | Gradle 8.14, Shadow JAR (fat JAR) |

## Команды Makefile

| Команда | Описание |
|---|---|
| `make build` | Собрать fat JAR через `./gradlew shadowJar` |
| `make run` | Запустить локально с dev-конфигурацией |
| `make package` | Собрать архив для деплоя (JAR + run.sh + systemd unit) |
| `make deploy` | Собрать и задеплоить на VPS по SSH |
