package com.zuluindustries.agenten.model;

/** Tokenverbrauch eines Modellaufrufs zusammen mit dem abgerechneten Modell. */
public interface ModelUsage {

    String model();

    TokenUsage usage();
}
