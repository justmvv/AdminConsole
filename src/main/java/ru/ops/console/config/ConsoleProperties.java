package ru.ops.console.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All console settings (prefix {@code console.*} in application.yml).
 */
@ConfigurationProperties(prefix = "console")
public class ConsoleProperties {

    private Ui ui = new Ui();
    private Security security = new Security();
    private Db db = new Db();
    private Kafka kafka = new Kafka();
    private Audit audit = new Audit();
    private Features features = new Features();
    private Artemis artemis = new Artemis();
    private Limits limits = new Limits();
    /**
     * Read-only mode: all writes are off regardless of other settings (DB insert/update, publishing to Kafka
     * and Artemis). Handy when the console is attached to someone else's system.
     */
    private boolean readOnly = false;

    public Ui getUi() { return ui; }
    public void setUi(Ui ui) { this.ui = ui; }
    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }
    public Db getDb() { return db; }
    public void setDb(Db db) { this.db = db; }
    public Kafka getKafka() { return kafka; }
    public void setKafka(Kafka kafka) { this.kafka = kafka; }
    public Audit getAudit() { return audit; }
    public void setAudit(Audit audit) { this.audit = audit; }
    public Features getFeatures() { return features; }
    public void setFeatures(Features features) { this.features = features; }
    public Artemis getArtemis() { return artemis; }
    public void setArtemis(Artemis artemis) { this.artemis = artemis; }
    public Limits getLimits() { return limits; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public void setLimits(Limits limits) { this.limits = limits; }

    // -------------------------------------------------------------- Limits
    /** Concurrency caps for heavy operations, shared by all users of one console instance. */
    public static class Limits {
        /** Simultaneous message browsing requests per system (Kafka, Artemis). */
        private int maxParallelBrowse = 4;
        /** Simultaneous CSV exports. */
        private int maxParallelExports = 2;
        /** How long a request waits for a free slot before failing, ms. */
        private long waitMs = 10_000;

        public int getMaxParallelBrowse() { return maxParallelBrowse; }
        public void setMaxParallelBrowse(int maxParallelBrowse) { this.maxParallelBrowse = maxParallelBrowse; }
        public int getMaxParallelExports() { return maxParallelExports; }
        public void setMaxParallelExports(int maxParallelExports) { this.maxParallelExports = maxParallelExports; }
        public long getWaitMs() { return waitMs; }
        public void setWaitMs(long waitMs) { this.waitMs = waitMs; }
    }

    // ------------------------------------------------------------ Features
    /** Which console sections are enabled. A disabled section is hidden in the menu, its views and services are unavailable. */
    public static class Features {
        private boolean db = true;
        private boolean kafka = true;
        private boolean artemis = false;

        public boolean isDb() { return db; }
        public void setDb(boolean db) { this.db = db; }
        public boolean isKafka() { return kafka; }
        public void setKafka(boolean kafka) { this.kafka = kafka; }
        public boolean isArtemis() { return artemis; }
        public void setArtemis(boolean artemis) { this.artemis = artemis; }

        public boolean isEnabled(Feature f) {
            return switch (f) {
                case DB -> db;
                case KAFKA -> kafka;
                case ARTEMIS -> artemis;
            };
        }
    }

    public enum Feature {
        DB("База данных"), KAFKA("Kafka"), ARTEMIS("Artemis");

        private final String label;

        Feature(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    // ------------------------------------------------------------- Artemis
    public static class Artemis {
        /** Broker URL: tcp://host:61616, several — (tcp://a:61616,tcp://b:61616); TLS — ?sslEnabled=true;trustStorePath=… */
        private String url = "tcp://localhost:61616";
        /** Account used for browsing. The browse permission on the needed queues is enough. */
        private String user;
        private String password;
        private int requestTimeoutMs = 15_000;
        /**
         * Queues to browse: exact names (work without management permissions) and masks with "*"
         * (filter for queues discovered via management). Empty — all discovered queues.
         */
        private List<String> queues = new ArrayList<>();
        private ArtemisManagement management = new ArtemisManagement();
        private ArtemisBrowse browse = new ArtemisBrowse();
        private ArtemisProduce produce = new ArtemisProduce();

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
        public List<String> getQueues() { return queues; }
        public void setQueues(List<String> queues) { this.queues = queues; }
        public ArtemisManagement getManagement() { return management; }
        public void setManagement(ArtemisManagement management) { this.management = management; }
        public ArtemisBrowse getBrowse() { return browse; }
        public void setBrowse(ArtemisBrowse browse) { this.browse = browse; }
        public ArtemisProduce getProduce() { return produce; }
        public void setProduce(ArtemisProduce produce) { this.produce = produce; }
    }

    /**
     * Management (queue list, message and consumer counts) is optional. Requires manage on
     * {@code activemq.management} and createNonDurableQueue/createAddress/consume/send on {@code <reply-prefix>.#}.
     * Without them the console works with the queues listed in {@code console.artemis.queues}.
     */
    public static class ArtemisManagement {
        private boolean enabled = true;
        private String address = "activemq.management";
        /** Prefix of the temporary queue for management replies. */
        private String replyPrefix = "admin-console.reply";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getReplyPrefix() { return replyPrefix; }
        public void setReplyPrefix(String replyPrefix) { this.replyPrefix = replyPrefix; }
    }

    public static class ArtemisBrowse {
        /** Max messages per result. */
        private int maxMessages = 500;
        /** Max messages scanned per browse (the queue is read from its head). */
        private int maxScan = 10_000;
        private int timeoutMs = 20_000;
        /** Bodies larger than this are not loaded (large messages). */
        private int maxBodyBytes = 1_048_576;

        public int getMaxMessages() { return maxMessages; }
        public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
        public int getMaxScan() { return maxScan; }
        public void setMaxScan(int maxScan) { this.maxScan = maxScan; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxBodyBytes() { return maxBodyBytes; }
        public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
    }

    public static class ArtemisProduce {
        private boolean enabled = true;
        /** Whitelist of addresses for publishing: exact name or mask with "*". Empty — publishing is disabled. */
        private List<String> allowedAddresses = new ArrayList<>();
        private boolean requireReason = true;
        /** Separate account for publishing (send permission). Empty — the browsing account is used. */
        private String user;
        private String password;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getAllowedAddresses() { return allowedAddresses; }
        public void setAllowedAddresses(List<String> allowedAddresses) { this.allowedAddresses = allowedAddresses; }
        public boolean isRequireReason() { return requireReason; }
        public void setRequireReason(boolean requireReason) { this.requireReason = requireReason; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    // ------------------------------------------------------------------ UI
    public static class Ui {
        /** Title in the header. */
        private String title = "Консоль сопровождения";
        /** Environment name: DEV / TEST / PROD. Shown as a colored badge. */
        private String environmentName = "DEV";
        /** Environment badge color (CSS). */
        private String environmentColor = "#2e7d32";

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getEnvironmentName() { return environmentName; }
        public void setEnvironmentName(String environmentName) { this.environmentName = environmentName; }
        public String getEnvironmentColor() { return environmentColor; }
        public void setEnvironmentColor(String environmentColor) { this.environmentColor = environmentColor; }
    }

    // ------------------------------------------------------------ Security
    public static class Security {
        public enum Mode { AD, LDAP }

        /** AD — Active Directory (bind by userPrincipalName), LDAP — classic LDAP (bind + group search). */
        private Mode mode = Mode.AD;
        private Ldap ldap = new Ldap();
        /**
         * Maps console roles to directory groups (by group CN, case-insensitive).
         * Keys: VIEWER, OPERATOR, ADMIN. ADMIN includes OPERATOR, OPERATOR includes VIEWER.
         */
        private Map<String, List<String>> roleMapping = new LinkedHashMap<>();
        private Session session = new Session();

        public Mode getMode() { return mode; }
        public void setMode(Mode mode) { this.mode = mode; }
        public Ldap getLdap() { return ldap; }
        public void setLdap(Ldap ldap) { this.ldap = ldap; }
        public Map<String, List<String>> getRoleMapping() { return roleMapping; }
        public void setRoleMapping(Map<String, List<String>> roleMapping) { this.roleMapping = roleMapping; }
        public Session getSession() { return session; }
        public void setSession(Session session) { this.session = session; }
    }

    public static class Session {
        /**
         * End the session after this long without user activity (clicks, keyboard, scrolling).
         * Vaadin heartbeats and background operations do not count as activity. 0 — never.
         */
        private Duration idleTimeout = Duration.ofMinutes(15);
        /** How long before the end to show a warning with a "Continue working" button. */
        private Duration warningBefore = Duration.ofSeconds(60);

        public Duration getIdleTimeout() { return idleTimeout; }
        public void setIdleTimeout(Duration idleTimeout) { this.idleTimeout = idleTimeout; }
        public Duration getWarningBefore() { return warningBefore; }
        public void setWarningBefore(Duration warningBefore) { this.warningBefore = warningBefore; }

        public boolean isEnabled() {
            return idleTimeout != null && idleTimeout.isPositive();
        }
    }

    public static class Ldap {
        /** Directory URL. For LDAP the base DN may be given in the path: ldap://host:389/dc=corp,dc=local */
        private String url = "ldap://localhost:389";
        /** AD: domain (corp.local) — the login becomes user@corp.local. */
        private String domain;
        /** AD: root DN for the user search (optional). */
        private String rootDn;
        /** AD: custom user search filter (optional). {0}=user@domain, {1}=user */
        private String adSearchFilter;
        /** LDAP: service account for searches (empty — anonymous search). */
        private String managerDn;
        private String managerPassword;
        /** LDAP: where and how to search for the user. {0} = entered login. */
        private String userSearchBase = "ou=people";
        private String userSearchFilter = "(uid={0})";
        /** LDAP: where and how to search for groups. {0} = user DN, {1} = login. */
        private String groupSearchBase = "ou=groups";
        private String groupSearchFilter = "(member={0})";

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public String getRootDn() { return rootDn; }
        public void setRootDn(String rootDn) { this.rootDn = rootDn; }
        public String getAdSearchFilter() { return adSearchFilter; }
        public void setAdSearchFilter(String adSearchFilter) { this.adSearchFilter = adSearchFilter; }
        public String getManagerDn() { return managerDn; }
        public void setManagerDn(String managerDn) { this.managerDn = managerDn; }
        public String getManagerPassword() { return managerPassword; }
        public void setManagerPassword(String managerPassword) { this.managerPassword = managerPassword; }
        public String getUserSearchBase() { return userSearchBase; }
        public void setUserSearchBase(String userSearchBase) { this.userSearchBase = userSearchBase; }
        public String getUserSearchFilter() { return userSearchFilter; }
        public void setUserSearchFilter(String userSearchFilter) { this.userSearchFilter = userSearchFilter; }
        public String getGroupSearchBase() { return groupSearchBase; }
        public void setGroupSearchBase(String groupSearchBase) { this.groupSearchBase = groupSearchBase; }
        public String getGroupSearchFilter() { return groupSearchFilter; }
        public void setGroupSearchFilter(String groupSearchFilter) { this.groupSearchFilter = groupSearchFilter; }
    }

    // ------------------------------------------------------------------ DB
    public static class Db {
        /** Schemas allowed for browsing. Empty — all except system schemas. */
        private List<String> schemas = new ArrayList<>();
        /** Timeout of any query, seconds. */
        private int queryTimeoutSeconds = 30;
        /** Max rows in a CSV export. */
        private int maxExportRows = 10_000;
        /** Show partitions of partitioned tables as separate entries. */
        private boolean showPartitions = false;
        private Insert insert = new Insert();
        private Update update = new Update();

        public List<String> getSchemas() { return schemas; }
        public void setSchemas(List<String> schemas) { this.schemas = schemas; }
        public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) { this.queryTimeoutSeconds = queryTimeoutSeconds; }
        public int getMaxExportRows() { return maxExportRows; }
        public void setMaxExportRows(int maxExportRows) { this.maxExportRows = maxExportRows; }
        public boolean isShowPartitions() { return showPartitions; }
        public void setShowPartitions(boolean showPartitions) { this.showPartitions = showPartitions; }
        public Insert getInsert() { return insert; }
        public void setInsert(Insert insert) { this.insert = insert; }
        public Update getUpdate() { return update; }
        public void setUpdate(Update update) { this.update = update; }
    }

    public static class Update {
        private boolean enabled = true;
        /**
         * Whitelist of editable columns: schema.table.column, masks with "*" (orch.retry_task.*).
         * Empty — editing is disabled. The DB account must also have the UPDATE privilege on the column.
         */
        private List<String> allowedColumns = new ArrayList<>();
        private boolean requireReason = true;
        /** Max rows in one update by filter. */
        private int maxRows = 1_000;
        /** How long to wait for rows locked by the application, seconds. */
        private int lockTimeoutSeconds = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getAllowedColumns() { return allowedColumns; }
        public void setAllowedColumns(List<String> allowedColumns) { this.allowedColumns = allowedColumns; }
        public boolean isRequireReason() { return requireReason; }
        public void setRequireReason(boolean requireReason) { this.requireReason = requireReason; }
        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
        public int getLockTimeoutSeconds() { return lockTimeoutSeconds; }
        public void setLockTimeoutSeconds(int lockTimeoutSeconds) { this.lockTimeoutSeconds = lockTimeoutSeconds; }
    }

    public static class Insert {
        private boolean enabled = true;
        /** Whitelist of tables for inserts: "schema.table", "schema.*", "*". Empty — inserts are disabled everywhere. */
        private List<String> allowedTables = new ArrayList<>();
        /** Require a justification (ticket/incident number). */
        private boolean requireReason = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getAllowedTables() { return allowedTables; }
        public void setAllowedTables(List<String> allowedTables) { this.allowedTables = allowedTables; }
        public boolean isRequireReason() { return requireReason; }
        public void setRequireReason(boolean requireReason) { this.requireReason = requireReason; }
    }

    // --------------------------------------------------------------- Kafka
    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        /** Any additional client properties: security.protocol, sasl.*, ssl.* ... */
        private Map<String, String> properties = new LinkedHashMap<>();
        private String clientIdPrefix = "admin-console";
        private int requestTimeoutMs = 15_000;
        private boolean hideInternalTopics = true;
        private Browse browse = new Browse();
        private Produce produce = new Produce();

        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
        public Map<String, String> getProperties() { return properties; }
        public void setProperties(Map<String, String> properties) { this.properties = properties; }
        public String getClientIdPrefix() { return clientIdPrefix; }
        public void setClientIdPrefix(String clientIdPrefix) { this.clientIdPrefix = clientIdPrefix; }
        public int getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
        public boolean isHideInternalTopics() { return hideInternalTopics; }
        public void setHideInternalTopics(boolean hideInternalTopics) { this.hideInternalTopics = hideInternalTopics; }
        public Browse getBrowse() { return browse; }
        public void setBrowse(Browse browse) { this.browse = browse; }
        public Produce getProduce() { return produce; }
        public void setProduce(Produce produce) { this.produce = produce; }
    }

    public static class Browse {
        /** Max messages per result. */
        private int maxMessages = 1_000;
        /** Max records scanned per partition when searching with a filter. */
        private int maxScanPerPartition = 50_000;
        /** Total read time budget, ms. */
        private int timeoutMs = 20_000;

        public int getMaxMessages() { return maxMessages; }
        public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
        public int getMaxScanPerPartition() { return maxScanPerPartition; }
        public void setMaxScanPerPartition(int maxScanPerPartition) { this.maxScanPerPartition = maxScanPerPartition; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class Produce {
        private boolean enabled = true;
        /** Whitelist of topics for publishing: exact name or mask with "*". Empty — publishing is disabled. */
        private List<String> allowedTopics = new ArrayList<>();
        private boolean requireReason = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getAllowedTopics() { return allowedTopics; }
        public void setAllowedTopics(List<String> allowedTopics) { this.allowedTopics = allowedTopics; }
        public boolean isRequireReason() { return requireReason; }
        public void setRequireReason(boolean requireReason) { this.requireReason = requireReason; }
    }

    // --------------------------------------------------------------- Audit
    public static class Audit {
        /** Write the audit log to a database table (in addition to logs/audit.log). */
        private boolean jdbcEnabled = true;
        /** Audit table: "table" or "schema.table". */
        private String jdbcTable = "admin_console.audit_log";
        /** Create the schema/table at startup if missing. */
        private boolean createTable = true;
        /**
         * Separate database for the audit log (JDBC URL). Empty — the audit table lives in the console's main
         * database. Use it when you must not create objects in the target system's database.
         */
        private String jdbcUrl;
        private String jdbcUser;
        private String jdbcPassword;

        public String getJdbcUrl() { return jdbcUrl; }
        public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
        public String getJdbcUser() { return jdbcUser; }
        public void setJdbcUser(String jdbcUser) { this.jdbcUser = jdbcUser; }
        public String getJdbcPassword() { return jdbcPassword; }
        public void setJdbcPassword(String jdbcPassword) { this.jdbcPassword = jdbcPassword; }
        public boolean hasSeparateDatabase() { return jdbcUrl != null && !jdbcUrl.isBlank(); }

        public boolean isJdbcEnabled() { return jdbcEnabled; }
        public void setJdbcEnabled(boolean jdbcEnabled) { this.jdbcEnabled = jdbcEnabled; }
        public String getJdbcTable() { return jdbcTable; }
        public void setJdbcTable(String jdbcTable) { this.jdbcTable = jdbcTable; }
        public boolean isCreateTable() { return createTable; }
        public void setCreateTable(boolean createTable) { this.createTable = createTable; }
    }
}
