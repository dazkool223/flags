package dev.flagkit.usage.spring;

import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Run this class from your IDE. The default {@code demo} profile uses in-memory flags and needs
 * no database. Pass {@code --spring.profiles.active=postgres} to read flags from Postgres
 * instead (see {@code application-postgres.properties}).
 */
@SpringBootApplication
public class UsageApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsageApplication.class, args);
    }

    @Bean
    CommandLineRunner demo(CheckoutService checkout, CurrentCustomer current) {
        return args -> {
            for (var customer : List.of(
                    new Customer("ana", "eu", "premium", false),
                    new Customer("bo", "eu", "free", true),
                    new Customer("cy", "eu", "free", false),
                    new Customer("di", "us", "premium", false))) {
                current.set(customer);
                try {
                    System.out.println(customer.id() + " -> " + checkout.checkout(List.of("tea", "mug")));
                } finally {
                    current.clear();
                }
            }
        };
    }
}
