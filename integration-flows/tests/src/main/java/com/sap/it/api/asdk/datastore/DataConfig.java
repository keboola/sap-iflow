package com.sap.it.api.asdk.datastore;

/** Where a data store entry goes: store name, id, overwrite, encryption, expiry. */
public class DataConfig {

    private String storeName;
    private String id;
    private boolean overwrite;
    private boolean encrypt;
    private long expires;

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

    public boolean isEncrypt() {
        return encrypt;
    }

    public void setEncrypt(final boolean encrypt) {
        this.encrypt = encrypt;
    }

    public long getExpires() {
        return expires;
    }

    public void setExpires(final long expires) {
        this.expires = expires;
    }
}
