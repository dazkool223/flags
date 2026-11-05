package dev.flagkit.usage.plain;

/**
 * The evaluation context. flagkit passes this object to every condition, so conditions read typed
 * fields and never cast values out of a map. One evaluator has one context type.
 */
public record Customer(String id, String region, String plan, boolean staff) {}
