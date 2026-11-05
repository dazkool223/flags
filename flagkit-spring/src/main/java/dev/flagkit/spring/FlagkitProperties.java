package dev.flagkit.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code flagkit.*}. */
@ConfigurationProperties("flagkit")
public class FlagkitProperties {

    /**
     * Prefix of this environment's flag tables, for example {@code prod_}. Empty or lowercase
     * letters, digits and underscores, starting with a letter, at most 16 characters.
     */
    private String tablePrefix = "";

    /** Load the flag graph at startup, so a bad graph fails the deployment. Default: load on first use. */
    private boolean eagerLoad = false;

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    public boolean isEagerLoad() {
        return eagerLoad;
    }

    public void setEagerLoad(boolean eagerLoad) {
        this.eagerLoad = eagerLoad;
    }
}
