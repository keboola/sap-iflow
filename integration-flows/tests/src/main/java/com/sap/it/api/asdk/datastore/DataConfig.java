package com.sap.it.api.asdk.datastore;

/** Where a data store entry goes: store name, id, overwrite. */
public class DataConfig {

    private String storeName;
    private String id;
    private boolean overwrite;

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(final String storeName) {
        this.storeName = storeName;
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public boolean isOverwrite() {
        return overwrite;
    }

    public void setOverwrite(final boolean overwrite) {
        this.overwrite = overwrite;
    }
}
