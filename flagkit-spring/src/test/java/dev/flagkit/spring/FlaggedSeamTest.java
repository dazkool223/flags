package dev.flagkit.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flagkit.Condition;
import dev.flagkit.FlagConfig;
import dev.flagkit.FlagDeclaration;
import dev.flagkit.FlagStore;
import dev.flagkit.InMemoryFlagStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Drives the Spring module through a real application context with an in-memory flag store. */
class FlaggedSeamTest {

    record Ctx(String region) {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlagkitAutoConfiguration.class));

    // --- fixtures ----------------------------------------------------------------------------

    /** Store, declaration, context provider and region condition shared by most tests. */
    @Configuration(proxyBeanMethods = false)
    static class Base {
        static final AtomicReference<Ctx> CURRENT = new AtomicReference<>(new Ctx("eu"));
        static final AtomicInteger PROVIDER_CALLS = new AtomicInteger();

        @Bean
        FlagStore store() {
            return new InMemoryFlagStore(
                    FlagConfig.of("checkout").on().condition("region-in", Map.of("regions", "eu,uk")).build());
        }

        @Bean
        FlagDeclaration checkoutFlag() {
            return new FlagDeclaration.PermanentFlag("checkout");
        }

        @Bean
        FlagDeclaration unstoredFlag() {
            return new FlagDeclaration.PermanentFlag("not-in-store");
        }

        @Bean
        FlagContextProvider<Ctx> contexts() {
            return () -> {
                PROVIDER_CALLS.incrementAndGet();
                return CURRENT.get();
            };
        }

        @Bean
        Condition<Ctx> regionIn() {
            return new Condition<>() {
                public String name() { return "region-in"; }
                public boolean test(Ctx c, Map<String, String> p) {
                    return List.of(p.get("regions").split(",")).contains(c.region());
                }
            };
        }
    }

    static class CheckoutService {
        @Flagged(key = "checkout", fallback = "legacy")
        public String checkout(String cart) {
            return "new:" + cart;
        }

        public String legacy(String cart) {
            return "legacy:" + cart;
        }

        @Flagged(key = "not-in-store", fallback = "off")
        public int count() {
            return 1;
        }

        int off() {
            return 0;
        }

        @Flagged(key = "checkout", fallback = "boom")
        public String throwing() {
            return "fine";
        }

        public String boom() {
            throw new IllegalStateException("from fallback");
        }

        /** Calls the seam through {@code this}, which bypasses the proxy. */
        public String internalCall(String cart) {
            return checkout(cart);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Service {
        @Bean
        CheckoutService checkoutService() {
            return new CheckoutService();
        }
    }

    private ApplicationContextRunner app() {
        Base.CURRENT.set(new Ctx("eu"));
        Base.PROVIDER_CALLS.set(0);
        return runner.withUserConfiguration(Base.class, Service.class);
    }

    // --- routing -----------------------------------------------------------------------------

    @Test
    void anEnabledFlagRunsTheSeamAndADisabledFlagRunsTheFallbackWithTheSameArguments() {
        app().run(context -> {
            var service = context.getBean(CheckoutService.class);

            assertThat(service.checkout("cart-1")).isEqualTo("new:cart-1");

            Base.CURRENT.set(new Ctx("us"));
            assertThat(service.checkout("cart-2")).isEqualTo("legacy:cart-2");
        });
    }

    @Test
    void theContextProviderIsAskedOncePerGuardedCall() {
        app().run(context -> {
            var service = context.getBean(CheckoutService.class);

            service.checkout("a");
            service.checkout("b");

            assertThat(Base.PROVIDER_CALLS).hasValue(2);
        });
    }

    @Test
    void aDeclaredFlagMissingFromTheStoreTakesTheFallback() {
        app().run(context -> assertThat(context.getBean(CheckoutService.class).count()).isZero());
    }

    @Test
    void aPackagePrivateFallbackWorks() {
        app().run(context -> assertThat(context.getBean(CheckoutService.class).count()).isZero());
    }

    @Test
    void anExceptionFromTheFallbackReachesTheCallerUnwrapped() {
        app().run(context -> {
            Base.CURRENT.set(new Ctx("us"));

            assertThatThrownBy(() -> context.getBean(CheckoutService.class).throwing())
                    .isInstanceOf(IllegalStateException.class).hasMessage("from fallback");
        });
    }

    @Test
    void aCallFromInsideTheSameClassSkipsTheFlag() {
        // The documented proxy limit: this.checkout() is not intercepted, so the seam always runs.
        app().run(context -> {
            Base.CURRENT.set(new Ctx("us"));

            assertThat(context.getBean(CheckoutService.class).internalCall("c")).isEqualTo("new:c");
        });
    }

    @Test
    void theEvaluatorIsAvailableToTheApplication() {
        app().run(context -> assertThat(context).hasSingleBean(dev.flagkit.Evaluator.class));
    }

    // --- startup failures --------------------------------------------------------------------

    static class MissingFallback {
        @Flagged(key = "checkout", fallback = "nowhere")
        public String run() {
            return "x";
        }
    }

    static class WrongParameters {
        @Flagged(key = "checkout", fallback = "other")
        public String run(String a) {
            return a;
        }

        public String other(int a) {
            return "x";
        }
    }

    static class WrongReturnType {
        @Flagged(key = "checkout", fallback = "other")
        public String run() {
            return "x";
        }

        public Integer other() {
            return 1;
        }
    }

    static class UndeclaredKey {
        @Flagged(key = "ghost", fallback = "other")
        public String run() {
            return "x";
        }

        public String other() {
            return "y";
        }
    }

    static class FinalSeam {
        @Flagged(key = "checkout", fallback = "other")
        public final String run() {
            return "x";
        }

        public String other() {
            return "y";
        }
    }

    static class PrivateSeam {
        @Flagged(key = "checkout", fallback = "other")
        private String run() {
            return "x";
        }

        public String other() {
            return "y";
        }
    }

    static class SelfFallback {
        @Flagged(key = "checkout", fallback = "run")
        public String run() {
            return "x";
        }
    }

    @Test
    void startupFailsForAMissingFallback() {
        assertStartupFails(MissingFallback.class, "fallback 'nowhere'", "no method nowhere()");
    }

    @Test
    void startupFailsForAFallbackWithDifferentParameters() {
        assertStartupFails(WrongParameters.class, "other", "(String)");
    }

    @Test
    void startupFailsForAFallbackWhoseReturnTypeDoesNotFit() {
        assertStartupFails(WrongReturnType.class, "returns String", "returns Integer");
    }

    @Test
    void startupFailsForAnUndeclaredFlagKey() {
        assertStartupFails(UndeclaredKey.class, "ghost", "checkout");
    }

    @Test
    void startupFailsForAFinalSeam() {
        assertStartupFails(FinalSeam.class, "private, final or static");
    }

    @Test
    void startupFailsForAPrivateSeam() {
        assertStartupFails(PrivateSeam.class, "private, final or static");
    }

    @Test
    void startupFailsWhenTheSeamIsItsOwnFallback() {
        assertStartupFails(SelfFallback.class, "itself");
    }

    @Test
    void startupFailsWithoutAContextProvider() {
        runner.withUserConfiguration(NoProvider.class, Service.class).run(context ->
                assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(FlagSeamException.class).hasMessageContaining("FlagContextProvider"));
    }

    @Configuration(proxyBeanMethods = false)
    static class NoProvider {
        @Bean
        FlagStore store() {
            return new InMemoryFlagStore(FlagConfig.of("checkout").on().build());
        }

        @Bean
        FlagDeclaration checkoutFlag() {
            return new FlagDeclaration.PermanentFlag("checkout");
        }

        @Bean
        FlagDeclaration unstoredFlag() {
            return new FlagDeclaration.PermanentFlag("not-in-store");
        }
    }

    @Test
    void startupFailsWhenAKeyIsDeclaredTwice() {
        runner.withUserConfiguration(Base.class, Service.class, DuplicateDeclaration.class).run(context ->
                assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(FlagSeamException.class).hasMessageContaining("declared more than once"));
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateDeclaration {
        @Bean
        FlagDeclaration again() {
            return new FlagDeclaration.PermanentFlag("checkout");
        }
    }

    private void assertStartupFails(Class<?> badBean, String... messageParts) {
        runner.withUserConfiguration(Base.class).withBean(badBean).run(context -> {
            assertThat(context).hasFailed();
            var root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(FlagSeamException.class);
            for (var part : messageParts) {
                assertThat(root.getMessage()).contains(part);
            }
        });
    }

    // --- configuration -----------------------------------------------------------------------

    @Test
    void anApplicationsOwnFlagStoreBeanWinsOverTheDataSourceOne() {
        app().withBean(javax.sql.DataSource.class, FlaggedSeamTest::dataSource).run(context ->
                assertThat(context.getBean(FlagStore.class)).isInstanceOf(InMemoryFlagStore.class));
    }

    @Test
    void aDataSourceBecomesAPostgresFlagStoreWithTheConfiguredPrefix() {
        runner.withBean(javax.sql.DataSource.class, FlaggedSeamTest::dataSource)
                .withPropertyValues("flagkit.table-prefix=prod_")
                .run(context -> assertThat(context.getBean(FlagStore.class))
                        .isInstanceOf(dev.flagkit.postgres.PostgresFlagStore.class));
    }

    @Test
    void aBadTablePrefixFailsStartup() {
        runner.withBean(javax.sql.DataSource.class, FlaggedSeamTest::dataSource)
                .withPropertyValues("flagkit.table-prefix=x; DROP TABLE flag")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void withoutADataSourceOrStoreNothingIsConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(dev.flagkit.Evaluator.class);
        });
    }

    @Test
    void eagerLoadFailsStartupOnABrokenGraphAndLazyLoadDoesNot() {
        var broken = new InMemoryFlagStore(FlagConfig.of("a").on().requires("missing").build());

        runner.withBean(FlagStore.class, () -> broken).withPropertyValues("flagkit.eager-load=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    var root = context.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root).isInstanceOf(dev.flagkit.InvalidGraphException.class)
                            .hasMessageContaining("missing");
                });
        runner.withBean(FlagStore.class, () -> broken)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** A data source that is never used to connect. Building the store must not touch the database. */
    private static javax.sql.DataSource dataSource() {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                FlaggedSeamTest.class.getClassLoader(), new Class<?>[] {javax.sql.DataSource.class},
                (proxy, method, args) -> {
                    throw new AssertionError("the data source must not be used at startup");
                });
    }
}
