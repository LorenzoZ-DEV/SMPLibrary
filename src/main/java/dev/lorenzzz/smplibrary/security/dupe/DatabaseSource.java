package dev.lorenzzz.smplibrary.security.dupe;

import java.nio.file.Path;

/**
 * Where the economy actually lives.
 *
 * <p>zEssentials writes to a SQLite file when {@code storage-type: SQLITE} and to MySQL or
 * MariaDB through a HikariCP pool for everything else ({@code HIKARICP}, {@code MYSQL}) — same
 * tables, same columns, entirely different way in. The probe has to follow whichever is in use,
 * so the destination is a value, not a hard-coded file path.
 */
public sealed interface DatabaseSource {

    /** One line naming the target, for logs and for {@code /dupewatch stats}. */
    String describe();

    /** A local file read in-place, opened read-only for every query. */
    record SqliteFile(Path file) implements DatabaseSource {

        public SqliteFile {
            if (file == null) throw new IllegalArgumentException("file is required");
        }

        @Override
        public String describe() {
            return "SQLite " + this.file.toAbsolutePath();
        }
    }

    /**
     * A JDBC server reached through a small pool.
     *
     * <p>The URL carries the driver family: {@code jdbc:mysql://…} or {@code jdbc:mariadb://…}
     * for what zEssentials calls HIKARICP. Credentials are held in memory only and never end
     * up in a message — {@link #describe()} deliberately prints the URL without them.
     */
    record Jdbc(String url, String username, String password) implements DatabaseSource {

        public Jdbc {
            if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
            if (!url.startsWith("jdbc:")) throw new IllegalArgumentException("not a JDBC url: " + url);
            username = username == null ? "" : username;
            password = password == null ? "" : password;
        }

        /** MySQL over the wire, whichever driver ends up speaking it. */
        public static Jdbc mysql(String host, int port, String database, String username, String password) {
            return new Jdbc("jdbc:mysql://" + host + ":" + port + "/" + database, username, password);
        }

        public static Jdbc mariadb(String host, int port, String database, String username, String password) {
            return new Jdbc("jdbc:mariadb://" + host + ":" + port + "/" + database, username, password);
        }

        @Override
        public String describe() {
            // The query string can carry credentials on some drivers, so it is cut off too.
            int query = this.url.indexOf('?');
            return query < 0 ? this.url : this.url.substring(0, query);
        }
    }
}
