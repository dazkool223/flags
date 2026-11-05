package dev.flagkit.postgres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The schema and roles scripts for one environment, with the table prefix filled in. */
public final class PostgresSchema {

    private PostgresSchema() {}

    /** Creates the environment's tables. Run it first, as the login that should own them. */
    public static String schemaSql(TablePrefix prefix) {
        return script("schema.sql", prefix);
    }

    /** Creates the reader, writer and owner roles and grants. Run it after the schema script. */
    public static String rolesSql(TablePrefix prefix) {
        return script("roles.sql", prefix);
    }

    public static String readerRole(TablePrefix prefix) {
        return "flagkit_" + prefix.value() + "reader";
    }

    public static String writerRole(TablePrefix prefix) {
        return "flagkit_" + prefix.value() + "writer";
    }

    public static String ownerRole(TablePrefix prefix) {
        return "flagkit_" + prefix.value() + "owner";
    }

    private static String script(String name, TablePrefix prefix) {
        try (var in = PostgresSchema.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${p}", prefix.value());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
