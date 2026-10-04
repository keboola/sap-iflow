package com.sap.it.api;

import java.util.HashMap;
import java.util.Map;

/** Test stand-in for ITApiFactory: answers the APIs a test registered, else null. */
public final class ITApiFactory {

    private static final Map<Class<?>, Object> SERVICES = new HashMap<Class<?>, Object>();

    private ITApiFactory() {
    }

    public static <T> T getService(final Class<T> type, final Object context) {
        return type.cast(SERVICES.get(type));
    }

    public static <T> T getApi(final Class<T> type, final Object context) {
        return getService(type, context);
    }

    public static <T> void register(final Class<T> type, final T service) {
        SERVICES.put(type, service);
    }

    public static void reset() {
        SERVICES.clear();
    }
}
