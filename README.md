# Admin Console — консоль сопровождения оркестратора

Web-консоль для сопровождения системы-оркестратора финансовых транзакций:

- **PostgreSQL** — просмотр схем и таблиц, постраничные данные с фильтрами и сортировкой, структура (колонки, ограничения, индексы), выгрузка в CSV, **вставка строк** (в т.ч. «копия существующей строки»), **изменение строк через форму** — одной строки или всех строк под фильтром. Ввода SQL в консоли нет;
- **Kafka** — топики (партиции, офсеты, конфигурация), чтение сообщений (последние N / с начала / с офсета / с момента времени, фильтры по ключу, значению и заголовкам), **consumer groups и lag**, **отправка сообщений** (в т.ч. переотправка найденного);
- **ActiveMQ Artemis** — очереди (с числом сообщений, если есть права management), **просмотр сообщений без их извлечения** — работает с одним правом `browse`, фильтры по телу, свойствам и ID, **отправка сообщений** (в т.ч. переотправка из DLQ на исходный адрес);
- **разделы включаются настройкой**: БД, Kafka, Artemis — в любом сочетании;
- **вход через Active Directory / LDAP**, роли по группам каталога;
- **журнал аудита** всех изменяющих действий (файл + таблица БД).

Стек: **Java 21, Spring Boot 3.5, Vaadin Flow 24.8** — backend и UI полностью на Java, без HTML/JS/CSS.

## Быстрый старт в Docker

Нужен только Docker (Maven и JDK локально не нужны — сборка идёт внутри образа).

```bash
docker compose up -d --build
```

Откройте http://localhost:8080. Тестовые пользователи (встроенный LDAP, профиль `dev`), пароль совпадает с логином:

| Логин      | Роль     | Что может                                                  |
|------------|----------|------------------------------------------------------------|
| `viewer`   | VIEWER   | смотреть таблицы и Kafka                                   |
| `operator` | OPERATOR | + вставка и изменение в БД, отправка в Kafka, выгрузка CSV |
| `admin`    | ADMIN    | + журнал аудита                                            |

Поднимается:

- `postgres` — PostgreSQL 16 с демо-схемой `orch` (платежи, шаги, outbox, задания на повтор, enum-типы, jsonb, секционированная таблица, view) и пользователем `console` с минимальными правами — `docker/postgres/init.sql`;
- `kafka` — Kafka 3.9 (KRaft, один узел); консоль при первом старте создаёт демо-топики `payments.*`, `ledger.postings` и consumer groups с отставанием;
- `console` — сама консоль.

С хоста: PostgreSQL — `localhost:5432`, Kafka — `localhost:29092`.

Первая сборка образа занимает несколько минут: скачиваются зависимости Maven и Node.js для сборки фронтенда Vaadin. Повторные сборки быстрее — используется кеш BuildKit.

```bash
docker compose logs -f console     # логи
docker compose down -v             # остановить и удалить данные
```

## Запуск на стенде

Шаблон — `docker-compose.prod.yml`: внешние PostgreSQL, Kafka и AD, дополнительный конфиг монтируется из `./config`.

```bash
export DB_USER=... DB_PASSWORD=...
mkdir -p config logs && cp docker/console-prod-example.yml config/console.yml   # поправить под себя
docker compose -f docker-compose.prod.yml up -d --build
```

Для закрытого контура:

- зеркало Maven: поправьте `docker/maven/settings.xml` и соберите с `--build-arg MVN_EXTRA_ARGS="-s /build/docker/maven/settings.xml"`;
- Node.js: плагин `vaadin-maven-plugin` скачивает его с nodejs.org. Если доступа нет, задайте в `pom.xml` параметр плагина `nodeDownloadRoot` (зеркало Node.js в Nexus) или соберите jar там, где есть доступ, и положите его в образ на базе `eclipse-temurin:21-jre`.

## Настройки

Все параметры — в `src/main/resources/application.yml`; любой из них можно переопределить переменной окружения.

| Переменная | Назначение |
|---|---|
| `CONSOLE_FEATURE_DB`, `CONSOLE_FEATURE_KAFKA`, `CONSOLE_FEATURE_ARTEMIS` | **какие разделы включены** (по умолчанию `true`, `true`, `false`). Выключенный раздел не виден в меню, его экраны не открываются даже по прямой ссылке, консоль не подключается к этой системе |
| `CONSOLE_READ_ONLY` | **режим «только чтение»**: выключает любые изменения (вставку и изменение в БД, отправку в Kafka и Artemis), что бы ни было в белых списках |
| `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE` | подключение к БД (пул по умолчанию — 8 соединений) |
| `CONSOLE_DB_SCHEMAS` | схемы, видимые в консоли (через запятую, маски `*`); пусто — все несистемные |
| `CONSOLE_DB_INSERT_ALLOWED_TABLES` | **белый список** таблиц для вставки: `orch.retry_task,orch.outbox`, `orch.*`; пусто — вставка запрещена |
| `CONSOLE_DB_UPDATE_ALLOWED_COLUMNS` | **белый список** изменяемых колонок: `orch.payment.status,orch.retry_task.*`; пусто — изменение запрещено |
| `CONSOLE_DB_UPDATE_MAX_ROWS` | максимум строк в одном изменении по фильтру (по умолчанию 1000) |
| `SESSION_IDLE_TIMEOUT`, `SESSION_IDLE_WARNING` | завершение сеанса при бездействии (по умолчанию `15m`, `0` — не завершать) и предупреждение до него (`60s`) |
| `KAFKA_BOOTSTRAP_SERVERS` | брокеры Kafka |
| `ARTEMIS_URL` | брокер Artemis: `tcp://host:61616`; кластер — `(tcp://a:61616,tcp://b:61616)`; TLS — `tcp://host:61617?sslEnabled=true;trustStorePath=…;trustStorePassword=…` |
| `ARTEMIS_USER`, `ARTEMIS_PASSWORD` | учётная запись для просмотра (см. «Права в Artemis») |
| `CONSOLE_ARTEMIS_QUEUES` | очереди для просмотра: точные имена работают без management, маски `orch.*` фильтруют найденные через management; пусто — все найденные |
| `CONSOLE_ARTEMIS_MANAGEMENT_ENABLED` | пытаться ли получать список очередей и счётчики через management (по умолчанию `true`; без прав консоль работает и так) |
| `CONSOLE_ARTEMIS_PRODUCE_ALLOWED_ADDRESSES` | **белый список** адресов для отправки (маски `*`); пусто — отправка запрещена |
| `ARTEMIS_PRODUCE_USER`, `ARTEMIS_PRODUCE_PASSWORD` | отдельная учётная запись для отправки (право `send`); пусто — та же, что для просмотра |
| `CONSOLE_KAFKA_PRODUCE_ALLOWED_TOPICS` | **белый список** топиков для отправки; пусто — отправка запрещена |
| `CONSOLE_SECURITY_MODE` | `AD` или `LDAP` |
| `LDAP_URL`, `AD_DOMAIN` | адрес контроллера домена (`ldaps://dc01.corp.local:636`) и домен (`corp.local`) |
| `CONSOLE_ROLE_VIEWER_GROUPS` / `_OPERATOR_GROUPS` / `_ADMIN_GROUPS` | CN групп каталога для ролей (через запятую) |
| `CONSOLE_ENV_NAME`, `CONSOLE_ENV_COLOR` | плашка стенда в шапке (PROD — красная) |
| `CONSOLE_AUDIT_JDBC_ENABLED`, `CONSOLE_AUDIT_TABLE` | запись аудита в таблицу (по умолчанию `admin_console.audit_log`) |
| `CONSOLE_AUDIT_DB_URL`, `CONSOLE_AUDIT_DB_USER`, `CONSOLE_AUDIT_DB_PASSWORD` | **отдельная БД для журнала аудита** — чтобы ничего не создавать в базе подключённой системы |
| `CONSOLE_MAX_PARALLEL_BROWSE`, `CONSOLE_MAX_PARALLEL_EXPORTS` | сколько просмотров сообщений (на каждую систему) и выгрузок CSV выполняется одновременно (4 и 2) |
| `SERVER_SERVLET_CONTEXT_PATH` | префикс пути, если консоль публикуется не в корне (`/admin`) |
| `TZ` | часовой пояс для времени в интерфейсе и логах (в образе по умолчанию `UTC`) |
| `CONSOLE_TITLE`, `CONSOLE_ENV_NAME`, `CONSOLE_ENV_COLOR` | заголовок и плашка стенда в шапке |

SASL/SSL для Kafka и списки групп удобнее задать в YAML-файле (пример — `docker/console-prod-example.yml`) и подключить через `SPRING_CONFIG_ADDITIONAL_LOCATION`.

### Проверка входа через Active Directory (эмулятор)

Для проверки режима `AD` без реального домена есть эмулятор — Samba 4 в роли контроллера домена `CORP.LOCAL`
(`docker/samba-ad`). Консоль при этом работает в боевом режиме `console.security.mode=AD`:

```bash
docker compose -f docker-compose.yml -f docker-compose.ad.yml up -d --build
```

Тестовые пользователи и пароли — в `docker/samba-ad/seed.sh`: по одному на каждую роль, пользователь без групп
консоли, член вложенной группы, отключённая учётка и учётка с требованием сменить пароль. Эти же сценарии
проверяет `ActiveDirectoryAuthIT` (`./mvnw verify`).

Поведение при входе:
- пароль верный, но учётка не входит ни в одну группу консоли — вход **запрещён** («Нет доступа к консоли»);
- требуется смена пароля (AD, код 773) — «Требуется сменить пароль»;
- остальные отказы (неверный пароль, учётка отключена или заблокирована) — общее сообщение, точная причина — в журнале аудита.

### Режим LDAP (не AD)

```yaml
console:
  security:
    mode: LDAP
    ldap:
      url: ldap://ldap.corp.local:389/dc=corp,dc=local
      manager-dn: cn=svc-console,ou=service,dc=corp,dc=local
      manager-password: ***
      user-search-base: ou=people
      user-search-filter: (uid={0})
      group-search-base: ou=groups
      group-search-filter: (member={0})
```

## Безопасность — как устроено

- **Роли**: ADMIN ⊃ OPERATOR ⊃ VIEWER. Права проверяются и в UI (`@RolesAllowed` на экранах), и повторно в сервисах перед изменяющими операциями.
- **Чтение БД** идёт в транзакциях `READ ONLY` с таймаутом (`console.db.query-timeout-seconds`). Даже если в код чтения попадёт модифицирующий SQL, PostgreSQL его отклонит.
- **Вставка**: только таблицы из белого списка **и** только при наличии у учётки БД права `INSERT`. Порядок: форма → предпросмотр SQL → обязательное обоснование (номер заявки/инцидента) → отдельная транзакция `INSERT … RETURNING *`. Запись аудита делается в той же транзакции: не записался аудит — откатилась и вставка.
- **Изменение** — без SQL от пользователя, только через форму:
  - меняются только колонки из белого списка **и** с правом `UPDATE` на колонку у учётки БД; ключ, identity и вычисляемые колонки — никогда; таблицы без первичного ключа не меняются;
  - **одна строка** (карточка строки → «Изменить…»): форма → «было / станет» → обоснование. Строка блокируется (`SELECT … FOR UPDATE`, `lock_timeout` 3 с) и сверяется с тем, что видел пользователь: если оркестратор успел её изменить, сохранения не будет — консоль покажет актуальные значения;
  - **по фильтру** («Изменить по фильтру…», только при заданном фильтре): число строк и примеры → обоснование → подтверждение вводом числа строк. Строки блокируются, и если под фильтр попадает не подтверждённое число строк — отказ; `UPDATE` адресуется по ключам заблокированных строк, лимит — `max-rows`;
  - в аудит (`DB_UPDATE`, `DB_BULK_UPDATE`) в той же транзакции пишутся ключи, прежние и новые значения, обоснование.
- **SQL-инъекции исключены**: имена схем, таблиц и колонок берутся только из `pg_catalog` и экранируются; значения всегда передаются параметрами. Тип значения выводит сам PostgreSQL по колонке (jsonb, uuid, enum, timestamptz, numeric).
- В `pg_stat_activity` сессии консоли видны как `application_name = admin-console:<логин>`.
- **Kafka**: чтение идёт без `group.id` (assign + seek) — офсеты рабочих consumer group не затрагиваются. Отправка: `acks=all`, идемпотентный producer, в сообщение добавляется заголовок `x-admin-console-user`.
- **Artemis**: просмотр — consumer в режиме browse-only: сообщения не забираются и не подтверждаются, в брокере ничего не создаётся. Сериализованные Java-объекты (`ObjectMessage`) консоль не десериализует. Отправка — текстовое сообщение (совместимо с JMS `TextMessage`), брокер подтверждает приём синхронно, добавляется свойство `x-admin-console-user`. Под отправку можно завести отдельную учётку брокера.
- **Аудит**: `LOGIN`, `LOGIN_FAILED`, `LOGOUT`, `SESSION_TIMEOUT`, `DB_VIEW`, `DB_EXPORT`, `DB_INSERT`, `DB_UPDATE`, `DB_BULK_UPDATE`, `KAFKA_BROWSE`, `KAFKA_PRODUCE`, `ARTEMIS_BROWSE`, `ARTEMIS_PRODUCE` — в `logs/audit.log` (JSON-строки, ротация по дням, хранение 400 дней) и в таблицу БД (экран «Журнал аудита» для ADMIN).

Рекомендуемые права для учётки консоли в БД (как в демо):

```sql
grant usage on schema orch to console;
grant select on all tables in schema orch to console;
grant insert on orch.retry_task, orch.outbox to console;   -- только то, что реально нужно
grant update (status) on orch.payment to console;          -- UPDATE — на отдельные колонки
grant usage on all sequences in schema orch to console;
create schema admin_console authorization console;         -- для журнала аудита
```

### Права в Artemis

Минимум для просмотра — **только `browse`** на нужные очереди; очереди при этом перечисляются в
`CONSOLE_ARTEMIS_QUEUES` точными именами. Всё остальное — по желанию:

| Что даёт | Права в брокере (`broker.xml` → `security-settings`) |
|---|---|
| просмотр сообщений | `browse` на очередь (адрес) |
| список очередей, число сообщений и потребителей | `manage` на `activemq.management`; `createNonDurableQueue`, `createAddress`, `consume`, `send` на `admin-console.reply.#` (временная очередь для ответов, префикс — `console.artemis.management.reply-prefix`) |
| отправка | `send` на адрес — лучше отдельной учётке (`ARTEMIS_PRODUCE_USER`) |

```xml
<security-setting match="orch.#">
   <!-- ... права приложений ... -->
   <permission type="browse" roles="app-orch,admin-console"/>
</security-setting>
```

Учтите: Artemis применяет только **самое точное** совпадение `security-setting`, права из `#` не наследуются —
в новом правиле нужно перечислить и роли приложений. Пример с ролями «только просмотр», «+ management» и
«отправка» — `docker/artemis/etc-override/broker.properties` (демо-брокер в `docker compose`).
Чтобы проверить работу на минимальных правах, укажите в `docker-compose.yml` `ARTEMIS_USER=console-ro`.

## Подключение к чужой системе

Консоль не привязана к конкретному приложению — всё, что она знает о системе, задаётся настройками.
Минимальный и безопасный вариант подключения к базе и брокерам, которыми владеет другая команда:

1. **Только нужные разделы**: `CONSOLE_FEATURE_DB` / `_KAFKA` / `_ARTEMIS` — консоль не подключается к выключенным системам.
2. **Сначала только чтение**: `CONSOLE_READ_ONLY=true`. Изменения включаются позже — точечно, белыми списками.
3. **Ничего не создавать в чужой БД**: журнал аудита — в отдельной базе (`CONSOLE_AUDIT_DB_URL`) или только в файле
   (`CONSOLE_AUDIT_JDBC_ENABLED=false`).
4. **Минимальные права учёток**:
   - PostgreSQL — `USAGE` на схему и `SELECT` на таблицы (плюс `INSERT` / `UPDATE (колонки)` — если нужны изменения);
     видимые схемы ограничиваются `CONSOLE_DB_SCHEMAS`; каждая сессия подписана в `pg_stat_activity` как `admin-console:<логин>`;
   - Kafka — `Describe`/`Read` на топики, `Describe` на группы; consumer group консоль не создаёт;
   - Artemis — только `browse` на очереди (см. «Права в Artemis»).
5. **Свои группы каталога** для ролей (`CONSOLE_ROLE_*_GROUPS`), свой заголовок и плашка стенда.
6. **Публикация за общим балансировщиком** — `SERVER_SERVLET_CONTEXT_PATH=/admin`, заголовки `X-Forwarded-*` учитываются.

Ограничения: поддерживается только PostgreSQL; интерфейс — на русском языке.

## Многопользовательская работа

- Каждый пользователь работает в своей сессии; фильтры, черновики сообщений и открытые экраны у пользователей не пересекаются.
- Одновременные изменения одной строки безопасны: строка блокируется и сверяется с тем, что видел пользователь, —
  из нескольких одновременных правок проходит одна, остальные получают «строка изменилась» (проверяется тестом).
- Тяжёлые операции (просмотр сообщений, выгрузка CSV) ограничены по числу одновременных запросов на весь экземпляр
  (`CONSOLE_MAX_PARALLEL_*`) — сверх лимита пользователь получит «повторите через несколько секунд», а не зависание.
- Все запросы к БД идут через общий пул (`DB_POOL_SIZE`, по умолчанию 8): для десятков одновременных пользователей
  с тяжёлыми фильтрами его стоит увеличить.
- **Несколько экземпляров** за балансировщиком — только с «липкими» сессиями (sticky sessions): сессия Vaadin живёт в памяти
  экземпляра. Журнал аудита у экземпляров общий, если они пишут в одну таблицу.

## Разработка без Docker

Нужен JDK 21 (Maven скачает `./mvnw`), а также запущенные PostgreSQL и Kafka (например, `docker compose up -d postgres kafka`).

```bash
SPRING_PROFILES_ACTIVE=dev \
DB_URL=jdbc:postgresql://localhost:5432/orchestrator \
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 \
CONSOLE_DB_SCHEMAS=orch,admin_console \
./mvnw spring-boot:run
```

Сборка production-jar: `./mvnw -Pproduction package` → `target/admin-console-1.0.0-SNAPSHOT.jar`.

## Тесты

```bash
./mvnw test      # unit-тесты: экранирование SQL, маски, форматы сообщений, маппинг групп AD → роли
./mvnw verify    # + интеграционные (*IT) на настоящих PostgreSQL, Kafka, Artemis и эмуляторе AD через Testcontainers — нужен Docker
```

Интеграционные тесты поднимают PostgreSQL с той же демо-схемой (`docker/postgres/init.sql`) и подключаются под
ограниченной учёткой `console`. Проверяются: фильтры и сортировка, защита от SQL-инъекций, отказ записи в
read-only транзакциях, белые списки и права на вставку, запись аудита в одной транзакции со вставкой и откат
при ошибке, CSV; для Kafka — чтение во всех режимах и с фильтрами без создания consumer group, lag групп,
отправка с заголовком `x-admin-console-user` и её ограничения.

## Структура

```
src/main/java/ru/ops/console
├── AdminConsoleApplication.java
├── config/      ConsoleProperties, FeatureGuard, Globs, DevKafkaSeeder / DevArtemisSeeder (dev)
├── security/    SecurityConfig (AD/LDAP), DirectoryGroupRoleMapper, CurrentUser, Roles
├── audit/       AuditService (файл + БД), AuditEvent, AuditAction
├── db/          DbMetadataService (pg_catalog), DbDataService (чтение/CSV/вставка), ReadOnlyJdbc, Sql
├── kafka/       KafkaClients, KafkaAdminService, KafkaBrowseService, KafkaProduceService, MessageFormat
├── artemis/     ArtemisClients, ArtemisQueueService (management — необязателен), ArtemisBrowseService, ArtemisProduceService
└── ui/          MainLayout, LoginView, HomeView, AuditView
    ├── db/      DbView, TablePanel, StructurePanel, RowDetailsDialog, InsertDialog
    ├── kafka/   TopicsView, TopicView, MessageBrowser, ConsumerGroupsView, GroupView, KafkaProduceView
    └── artemis/ ArtemisQueuesView, ArtemisQueueView, ArtemisProduceView
```

## Ограничения и идеи для развития

- Сообщения в Avro/Protobuf (Confluent Schema Registry) распознаются по magic byte и показываются в HEX с id схемы; декодирование можно добавить через `kafka-avro-serializer`.
- Вложенные группы AD не раскрываются: роли берутся из прямого `memberOf` пользователя (проверено на эмуляторе — член группы, вложенной в группу операторов, доступа не получает).
- Группы сопоставляются с ролями по CN; если в домене группы с таким CN могут создавать делегированные администраторы OU, надёжнее сопоставлять по полному DN.
- Удаление строк, ввод произвольного SQL и сброс офсетов consumer group намеренно не реализованы.
- Поддерживаются одна БД, один кластер Kafka и один брокер (кластер) Artemis; несколько подключений — следующий шаг.
- Artemis: без прав management видны только очереди, перечисленные в настройках, и без числа сообщений; очередь просматривается с головы, не более `console.artemis.browse.max-scan` сообщений за раз. Отправляются только текстовые сообщения.

## Лицензия

[MIT](LICENSE)
