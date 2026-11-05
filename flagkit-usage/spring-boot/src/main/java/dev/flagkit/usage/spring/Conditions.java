package dev.flagkit.usage.spring;

import dev.flagkit.Condition;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Conditions are beans. flagkit-spring registers every {@code Condition} bean with the evaluator
 * by its {@link Condition#name()}. The store refers to that name, never to the class.
 */
public final class Conditions {

    private Conditions() {}

    @Component
    public static class RegionIn implements Condition<Customer> {
        @Override
        public String name() {
            return "region-in";
        }

        @Override
        public boolean test(Customer customer, Map<String, String> params) {
            return List.of(params.get("regions").split(",")).contains(customer.region());
        }
    }

    @Component
    public static class PlanIs implements Condition<Customer> {
        @Override
        public String name() {
            return "plan-is";
        }

        @Override
        public boolean test(Customer customer, Map<String, String> params) {
            return customer.plan().equals(params.get("plan"));
        }
    }

    @Component
    public static class Staff implements Condition<Customer> {
        @Override
        public String name() {
            return "staff";
        }

        @Override
        public boolean test(Customer customer, Map<String, String> params) {
            return customer.staff();
        }
    }
}
