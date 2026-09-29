package com.keboola.cpi.adapter;

/** Storage token lookup. */
public interface TokenResolver {

    String resolve(String credentialName) throws Exception;
}
