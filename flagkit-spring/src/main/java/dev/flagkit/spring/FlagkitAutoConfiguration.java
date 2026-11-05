package dev.flagkit.spring;

import dev.flagkit.Condition;
import dev.flagkit.Evaluator;
import dev.flagkit.FlagDeclaration;
import dev.flagkit.FlagStore;
import dev.flagkit.postgres.PostgresFlagStore;
import dev.flagkit.postgres.TablePrefix;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.beans.factory.config.BeanDefinition;

/**
 * Builds the evaluator from the application's data source, the {@code Condition} beans and the
 * {@code FlagDeclaration} beans, and turns on {@link Flagged}. Every piece backs off if the
 * application defines its own bean of the same type.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@ConditionalOnClass(Evaluator.class)
@EnableConfigurationProperties(FlagkitProperties.class)
public class FlagkitAutoConfiguration {

    /** Reads the flag graph from the application's data source (use a reader login). */
    @Bean
    @ConditionalOnMissingBean(FlagStore.class)
    @ConditionalOnSingleCandidate(DataSource.class)
    FlagStore flagkitFlagStore(DataSource dataSource, FlagkitProperties properties) {
        return new PostgresFlagStore(dataSource, TablePrefix.of(properties.getTablePrefix()));
    }

    /**
     * One evaluator for the whole application. Its context type is whatever the single
     * {@link FlagContextProvider} returns, so conditions are registered untyped here.
     */
    @Bean
    @ConditionalOnBean(FlagStore.class)
    @ConditionalOnMissingBean
    @SuppressWarnings("unchecked")
    Evaluator<Object> flagkitEvaluator(FlagStore store, ObjectProvider<Condition<?>> conditions) {
        var builder = Evaluator.<Object>builder(store);
        conditions.orderedStream().forEach(condition -> builder.condition((Condition<Object>) condition));
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "flagkit", name = "eager-load", havingValue = "true")
    @ConditionalOnBean(Evaluator.class)
    SmartInitializingSingleton flagkitEagerLoad(ObjectProvider<Evaluator<Object>> evaluator) {
        return () -> evaluator.getObject().load();
    }

    /** Static, so Spring can create it before the beans it will proxy. */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static FlagSeamPostProcessor flagkitSeamPostProcessor(ObjectProvider<FlagDeclaration> declarations,
                                                          ObjectProvider<Evaluator<Object>> evaluator,
                                                          ObjectProvider<FlagContextProvider<?>> contexts) {
        return new FlagSeamPostProcessor(declarations, evaluator, contexts);
    }
}
