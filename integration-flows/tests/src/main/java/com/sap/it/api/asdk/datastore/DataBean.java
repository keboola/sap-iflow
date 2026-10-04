package com.sap.it.api.asdk.datastore;

/** One data store entry: its bytes. */
public class DataBean {

    private byte[] data;

    public byte[] getDataAsArray() {
        return data;
    }

    public void setDataAsArray(final byte[] data) {
        this.data = data;
    }
}
