package com.sap.it.api.asdk.datastore;

/** Data store operations the scripts use; a test implements it with a map coerced to this interface. */
public interface DataStoreService {

    DataBean get(String storeName, String id);

    void put(DataBean bean, DataConfig config);
}
