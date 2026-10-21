package com.sidehustle.daily.model;

/** A demand already stored in the demands table, shown to the model so it reuses the same pain_key. */
public record KnownDemand(String painKey, String painPoint) {}
