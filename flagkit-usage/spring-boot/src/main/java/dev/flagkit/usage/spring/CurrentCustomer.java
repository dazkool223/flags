package dev.flagkit.usage.spring;

import org.springframework.stereotype.Component;

/**
 * Stand-in for "who is making this request". In a web app this would read the security context
 * or a request-scoped bean. A thread-local keeps the example free of a web stack.
 */
@Component
public class CurrentCustomer {

    private final ThreadLocal<Customer> current = new ThreadLocal<>();

    public void set(Customer customer) {
        current.set(customer);
    }

    public Customer get() {
        return current.get();
    }

    public void clear() {
        current.remove();
    }
}
