package dev.flagkit.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TablePrefixTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "p", "prod_", "stage2_", "abcdefghijklmnop"})
    void acceptsLowercasePrefixesOfUpToSixteenCharacters(String prefix) {
        assertThat(TablePrefix.of(prefix).value()).isEqualTo(prefix);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Prod_", "1prod", "_prod", "prod-", "prod ", "abcdefghijklmnopq",
            "a; DROP TABLE flag; --", "a\"b", "a.b", "é"})
    void rejectsAnythingElseBeforeAnySqlIsBuilt(String prefix) {
        assertThatThrownBy(() -> TablePrefix.of(prefix)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> TablePrefix.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theScriptsContainTheValidatedPrefixAndNoPlaceholder() {
        var prefix = TablePrefix.of("prod_");

        assertThat(PostgresSchema.schemaSql(prefix)).contains("CREATE TABLE prod_flag")
                .doesNotContain("${p}");
        assertThat(PostgresSchema.rolesSql(prefix)).contains("flagkit_prod_writer")
                .doesNotContain("${p}");
    }
}
