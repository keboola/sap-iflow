package com.sap.it.api.asdk.runtime;

import java.util.HashMap;
import java.util.Map;

/** Test stand-in for the ASDK service factory: answers the services a test registered, else null. */
public class Factory {

    private static final Map<Class<?>, Object> SERVICES = new HashMap<Class<?>, Object>();

    private final Class<?> type;

    public Factory(final Class<?> type) {
        this.type = type;
    }

    @SuppressWarnings("unchecked")
    public <T> T getService() {
        return (T) SERVICES.get(type);
    }

    public static <T> void register(final Class<T> type, final T service) {
        SERVICES.put(type, service);
    }

    public static void reset() {
        SERVICES.clear();
    }
}
