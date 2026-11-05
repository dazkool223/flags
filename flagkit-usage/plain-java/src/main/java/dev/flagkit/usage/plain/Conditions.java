package dev.flagkit.usage.plain;

import dev.flagkit.Condition;
import java.util.List;
import java.util.Map;

/**
 * Conditions are ordinary Java classes. Each has a stable name that the store refers to (never
 * the Java class name, so you can rename or move the class). A condition gets the context and the
 * flag's params, and knows nothing about other flags. One condition can serve many flags by
 * reading different params.
 */
public final class Conditions {

    private Conditions() {}

    /** True when the customer's region is in the comma-separated {@code regions} param. */
    public static final class RegionIn implements Condition<Customer> {
        @Override
        public String name() {
            return "region-in";
        }

        @Override
        public boolean test(Customer customer, Map<String, String> params) {
            return List.of(params.get("regions").split(",")).contains(customer.region());
        }
    }

    /** True when the customer's plan equals the {@code plan} param. */
    public static final class PlanIs implements Condition<Customer> {
        @Override
        public String name() {
            return "plan-is";
        }

        @Override
        public boolean test(Customer customer, Map<String, String> params) {
            return customer.plan().equals(params.get("plan"));
        }
    }

    /** True for internal staff. Takes no params. */
    public static final class Staff implements Condition<Customer> {
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
