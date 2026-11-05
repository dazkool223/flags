package dev.flagkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionSettingsTest {

    @TempDir Path dir;

    private Path config(String... lines) throws IOException {
        return Files.writeString(dir.resolve("config.properties"), String.join("\n", lines));
    }

    @Test
    void readsAnEnvironmentWithDefaults() throws IOException {
        var file = config("dev.host=localhost", "dev.database=flags", "dev.user=alice");

        var settings = ConnectionSettings.load(file, "dev");

        assertThat(settings.port()).isEqualTo(5432);
        assertThat(settings.prefix().value()).isEmpty();
        assertThat(settings.describe()).isEqualTo("host=localhost database=flags user=alice");
    }

    @Test
    void aRemoteHostDefaultsToFullCertificateVerification() throws IOException {
        var file = config("prod.host=db.example.com", "prod.database=flags", "prod.user=alice", "prod.prefix=prod_");

        var settings = ConnectionSettings.load(file, "prod");

        assertThat(settings.sslMode()).isEqualTo("verify-full");
        assertThat(settings.prefix().value()).isEqualTo("prod_");
    }

    @Test
    void localHostsDefaultToPrefer() throws IOException {
        for (var host : new String[] {"localhost", "127.0.0.1", "::1"}) {
            var file = config("dev.host=" + host, "dev.database=d", "dev.user=u");
            assertThat(ConnectionSettings.load(file, "dev").sslMode()).isEqualTo("prefer");
        }
    }

    @Test
    void aRemoteHostCannotBeWeakenedBelowFullVerification() throws IOException {
        for (var mode : new String[] {"disable", "allow", "prefer", "require", "verify-ca"}) {
            var file = config("prod.host=db.example.com", "prod.database=d", "prod.user=u", "prod.sslmode=" + mode);
            assertThatThrownBy(() -> ConnectionSettings.load(file, "prod"))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("verify-full");
        }
    }

    @Test
    void anUnknownEnvironmentListsTheKnownOnes() throws IOException {
        var file = config("dev.host=localhost", "dev.database=d", "dev.user=u",
                "staging.host=h", "staging.database=d", "staging.user=u");

        assertThatThrownBy(() -> ConnectionSettings.load(file, "prod"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("dev, staging");
    }

    @Test
    void aMissingRequiredSettingIsNamed() throws IOException {
        var file = config("dev.host=localhost", "dev.user=u");

        assertThatThrownBy(() -> ConnectionSettings.load(file, "dev"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("database");
    }

    @Test
    void aPasswordInTheConfigFileIsRefusedWithoutEchoingIt() throws IOException {
        var file = config("dev.host=localhost", "dev.database=d", "dev.user=u", "dev.password=hunter2");

        assertThatThrownBy(() -> ConnectionSettings.load(file, "dev"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(".pgpass")
                .hasMessageNotContaining("hunter2");
    }

    @Test
    void anInvalidTablePrefixIsRefused() throws IOException {
        var file = config("dev.host=localhost", "dev.database=d", "dev.user=u", "dev.prefix=a; DROP TABLE x");

        assertThatThrownBy(() -> ConnectionSettings.load(file, "dev")).isInstanceOf(ConfigException.class);
    }

    @Test
    void aMissingConfigFileSaysHowToPointAtOne() {
        assertThatThrownBy(() -> ConnectionSettings.load(dir.resolve("nope.properties"), "dev"))
                .isInstanceOf(ConfigException.class).hasMessageContaining("--config");
    }

    @Test
    void driverMessagesLoseConnectionStringsAndPasswords() {
        var cleaned = Commands.sanitize("FATAL for jdbc:postgresql://h:5432/d?user=u&password=hunter2 password=hunter2");

        assertThat(cleaned).doesNotContain("jdbc:").doesNotContain("hunter2");
    }
}
