package com.keboola.cpi.adapter;

import java.util.Map;

import org.apache.camel.Endpoint;
import org.apache.camel.support.DefaultComponent;

public class KeboolaComponent extends DefaultComponent {

    @Override
    protected Endpoint createEndpoint(final String uri, final String remaining, final Map<String, Object> parameters) throws Exception {
        final KeboolaEndpoint endpoint = new KeboolaEndpoint(uri, this);
        setProperties(endpoint, parameters);
        return endpoint;
    }
}
