package dev.lorenzzz.smplibrary.manager;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.lorenzzz.smplibrary.Smp_library;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * HikariCP pool backed by storage.yml. SQLite works zero-config (driver bundled
 * by Paper server runtime). MySQL/MariaDB need the matching JDBC driver on the
 * server classpath.
 */
public final class StorageManager implements Manager {

    private final Smp_library plugin;
    private HikariDataSource dataSource;
    private String prefix;

    public StorageManager(Smp_library plugin) {
        this.plugin = plugin;
    }

    @Override public String name() { return "storage"; }

    @Override
    public void enable() {
        File file = new File(plugin.getDataFolder(), "storage.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);

        String type = y.getString("database.type", "sqlite").toLowerCase();
        prefix = y.getString("database.prefix", "smp_");

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("smp-library-pool");
        hc.setMaximumPoolSize(y.getInt("pool.maximum-pool-size", 10));
        hc.setMinimumIdle(y.getInt("pool.minimum-idle", 5));
        hc.setConnectionTimeout(y.getLong("pool.connection-timeout", 30000L));
        hc.setIdleTimeout(y.getLong("pool.idle-timeout", 600000L));
        hc.setMaxLifetime(y.getLong("pool.max-lifetime", 1800000L));
        hc.setLeakDetectionThreshold(y.getLong("pool.leak-detection-threshold", 0L));

        switch (type) {
            case "mysql" -> configureMysql(hc, y, "com.mysql.cj.jdbc.Driver");
            case "mariadb" -> configureMysql(hc, y, "org.mariadb.jdbc.Driver");
            default -> configureSqlite(hc, y);
        }

        dataSource = new HikariDataSource(hc);
        plugin.getLogger().info("Storage ready (" + type + ").");
    }

    private void configureMysql(HikariConfig hc, YamlConfiguration y, String driver) {
        String host = y.getString("database.host", "localhost");
        int port = y.getInt("database.port", 3306);
        String name = y.getString("database.name", "smp_library");
        String params = y.getString("database.params", "");
        hc.setDriverClassName(driver);
        hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + name + (params.isEmpty() ? "" : "?" + params));
        hc.setUsername(y.getString("database.username", "root"));
        hc.setPassword(y.getString("database.password", ""));
    }

    private void configureSqlite(HikariConfig hc, YamlConfiguration y) {
        File db = new File(plugin.getDataFolder(), y.getString("database.name", "smp_library") + ".db");
        // ponytail: sqlite + Hikari sizing — no idle/max-lifetime churn, single idle conn.
        hc.setDriverClassName("org.sqlite.JDBC");
        hc.setJdbcUrl("jdbc:sqlite:" + db.getAbsolutePath());
        hc.setMaxLifetime(0);
        hc.setIdleTimeout(0);
        hc.setMinimumIdle(1);
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public String prefix() { return prefix; }

    public HikariDataSource dataSource() { return dataSource; }

    public void execute(String sql) {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            plugin.getLogger().warning("SQL error: " + e.getMessage());
        }
    }

    @Override
    public void disable() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}