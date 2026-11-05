package dev.flagkit.usage.spring;

/** The evaluation context. Every Condition bean and the context provider use this one type. */
public record Customer(String id, String region, String plan, boolean staff) {}
