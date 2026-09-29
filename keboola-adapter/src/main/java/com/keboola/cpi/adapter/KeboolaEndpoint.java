package com.keboola.cpi.adapter;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.apache.camel.support.DefaultEndpoint;

/** Keboola receiver endpoint. */
@UriEndpoint(scheme = "keboola", syntax = "keboola:target", title = "Keboola")
public class KeboolaEndpoint extends DefaultEndpoint {

    @UriParam
    private String stackUrl;

    @UriParam
    private String credentialName = "KEBOOLA_STORAGE";

    @UriParam
    private String tableId;

    @UriParam
    private boolean incremental = true;

    @UriParam
    private int timeoutMs = 60000;

    @UriParam
    private String importerUrl;

    private TokenResolver tokenResolver = new SecureStoreTokenResolver();

    private volatile String resolvedImporterUrl;

    public KeboolaEndpoint(final String uri, final KeboolaComponent component) {
        super(uri, component);
    }

    @Override
    public Producer createProducer() throws Exception {
        return new KeboolaProducer(this);
    }

    @Override
    public Consumer createConsumer(final Processor processor) throws Exception {
        throw new UnsupportedOperationException("The Keboola adapter is receiver-only");
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    public String getStackUrl() {
        return stackUrl;
    }

    public void setStackUrl(final String stackUrl) {
        this.stackUrl = stackUrl;
    }

    public String getCredentialName() {
        return credentialName;
    }

    public void setCredentialName(final String credentialName) {
        this.credentialName = credentialName;
    }

    public String getTableId() {
        return tableId;
    }

    public void setTableId(final String tableId) {
        this.tableId = tableId;
    }

    public boolean isIncremental() {
        return incremental;
    }

    public void setIncremental(final boolean incremental) {
        this.incremental = incremental;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(final int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getImporterUrl() {
        return importerUrl;
    }

    public void setImporterUrl(final String importerUrl) {
        this.importerUrl = importerUrl;
    }

    TokenResolver getTokenResolver() {
        return tokenResolver;
    }

    void setTokenResolver(final TokenResolver tokenResolver) {
        this.tokenResolver = tokenResolver;
    }

    String getResolvedImporterUrl() {
        return resolvedImporterUrl;
    }

    void setResolvedImporterUrl(final String resolvedImporterUrl) {
        this.resolvedImporterUrl = resolvedImporterUrl;
    }
}
