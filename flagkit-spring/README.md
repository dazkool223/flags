# flagkit-spring

Spring Boot 4 auto-configuration for flagkit, plus the `@Flagged` annotation.

## Setup

Add `flagkit-spring` and a Postgres JDBC driver. If the application has one `DataSource`, flagkit builds a Postgres flag store from it. Connect that data source as the environment's reader login (see `roles.sql` in `flagkit-postgres`).

```properties
flagkit.table-prefix=prod_   # this environment's table prefix; empty by default
flagkit.eager-load=true      # load the graph at startup so a bad graph fails the deploy
```

Then define four kinds of bean:

```java
@Bean FlagDeclaration checkoutFlag() {                 // one per flag the code uses
    return new FlagDeclaration.ReleaseFlag("checkout", LocalDate.of(2027, 3, 31));
}

@Bean Condition<Ctx> regionIn() { ... }                // one per condition name used in the store

@Bean FlagContextProvider<Ctx> flagContext() {         // exactly one
    return () -> new Ctx(currentUser().region());
}
```

flagkit registers an `Evaluator<Object>` bean from the data source and the `Condition` beans. Define your own `FlagStore` bean to replace the Postgres store, for example `InMemoryFlagStore` in tests.

## The seam

```java
@Service
class CheckoutService {

    @Flagged(key = "checkout", fallback = "legacyCheckout")
    public Receipt checkout(Cart cart) { /* new flow */ }

    Receipt legacyCheckout(Cart cart) { /* old flow */ }
}
```

When `checkout` is enabled for the current context, `checkout(cart)` runs. Otherwise `legacyCheckout(cart)` runs with the same arguments. The context comes from the `FlagContextProvider`, which is called once per guarded call.

Put the annotation on the one method where the decision is made. Pass the result of that decision inward as a parameter or a collaborator, and do not check the flag again deeper in the code. Removing the flag later then means deleting one `@Flagged`, the fallback, and the declaration.

A declared flag that has no row in the store is disabled with reason "flag not found", so the fallback runs. Code can ship before the flag is created.

## Startup checks

The application fails to start, with the method named in the message, when:

- the flag key has no `FlagDeclaration` bean, or two beans declare it;
- the fallback does not exist, takes different parameter types, or returns a type the seam's return type cannot hold;
- the fallback is the seam itself;
- the seam is `private`, `final` or `static`, or its class is `final`;
- there is no `FlagContextProvider` bean.

## Calls from inside the same class are not guarded

The flag check runs in a Spring proxy around the bean. A call to another bean goes through the proxy. A call from inside the same class (`this.checkout(cart)`, or just `checkout(cart)`) does not, so it always runs the annotated method and ignores the flag.

```java
@Service
class OrderService {
    public void place(Order order) {
        checkout(order.cart());      // NOT guarded: calls the real method directly
    }

    @Flagged(key = "checkout", fallback = "legacyCheckout")
    public Receipt checkout(Cart cart) { ... }
}
```

Move the seam to a method that other beans call, or move it into its own bean. `FlaggedSeamTest.aCallFromInsideTheSameClassSkipsTheFlag` pins this behavior.

Seams use class-based proxies, so the bean class needs a visible constructor that Spring can call and cannot be `final`.
