package com.sap.it.api.securestore;

/** Secure store lookup the scripts use; a test implements it with a map coerced to this interface. */
public interface SecureStoreService {

    UserCredential getUserCredential(String alias);
}
