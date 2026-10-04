package com.sap.it.api.mapping;

/** Value Mapping lookup; a test implements it with a map coerced to this interface. */
public interface ValueMappingApi {

    String getMappedValue(String sourceAgency, String sourceIdentifier, String sourceValue,
                          String targetAgency, String targetIdentifier);
}
