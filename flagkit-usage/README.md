# flagkit-usage

Runnable, tested examples of how to use flagkit. This is not part of the library. Nothing here is published, and the library never depends on it. The modules are in the build so that every example compiles and runs its tests whenever the library changes. An example that stops working fails the build.

Both projects use the same scenario: the new checkout applies only in beta regions, and only for premium customers or internal staff.

```
checkout                      ALL of
|-- beta-region               condition region-in {regions=eu,uk}
`-- premium-or-internal       ANY of
    |-- premium-plan          condition plan-is {plan=premium}
    `-- internal-staff        condition staff
```

## Where to look

| You want to | Plain Java (`plain-java/`) | Spring Boot (`spring-boot/`) |
| --- | --- | --- |
| See the smallest working use | `InMemoryExample` | `UsageApplication` with the default `demo` profile |
| Write a condition | `Conditions` | `Conditions` (beans) |
| Declare flags in code | `CheckoutFlags` | `FlagSetup` |
| Put the decision point in your code | `CheckoutService` (an `if`) | `CheckoutService` (`@Flagged`) |
| Unit-test code behind a flag | `CheckoutServiceTest` | `DemoProfileTest` |
| See load failures and "flag not found" | `InMemoryExampleTest` | |
| Use Postgres | `PostgresExample`, `PostgresExampleTest` | `application-postgres.properties`, `PostgresProfileTest` |

## Stores

| Store | Module | Plain Java | Spring Boot |
| --- | --- | --- | --- |
| In memory (tests, demos) | `flagkit-core` | `InMemoryExample`, `CheckoutFlags.inMemoryGraph()` | `DemoStore`, profile `demo` |
| Postgres | `flagkit-postgres` | `PostgresExample` | profile `postgres` |

### Adding a store means adding its usage here

Whenever a new flag store is added to the library (MySQL, Oracle, Consul, S3, anything in `docs/later.md`), the same change must add its usage to both projects:

1. A plain Java example class and a test (`<Store>Example`, `<Store>ExampleTest`), showing who sets the store up, who writes flags, and how the application reads them.
2. A Spring Boot profile (`application-<store>.properties`) and a test (`<Store>ProfileTest`) that starts the real app against it.
3. A new row in the table above.

A store that needs infrastructure uses Testcontainers with `@Testcontainers(disabledWithoutDocker = true)`, like the Postgres tests. Those tests skip on machines without Docker.

## Running

```
./mvnw -pl flagkit-usage/plain-java -am test      # plain Java
./mvnw -pl flagkit-usage/spring-boot -am test     # Spring Boot
```

The in-memory examples run anywhere. The Postgres tests need Docker.

To see output instead of assertions, run `InMemoryExample` or `UsageApplication` from your IDE. For a real Postgres, create the tables and roles with the scripts from `PostgresSchema` (see the `PostgresExample` Javadoc for who runs what), then run `PostgresExample seed` as an operator and `PostgresExample evaluate` as the application, or start `UsageApplication` with `--spring.profiles.active=postgres`. Passwords always come from `.pgpass`.
