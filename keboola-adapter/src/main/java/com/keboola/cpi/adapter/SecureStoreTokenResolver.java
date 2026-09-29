package com.keboola.cpi.adapter;

import com.sap.it.api.ITApiFactory;
import com.sap.it.api.securestore.SecureStoreService;
import com.sap.it.api.securestore.UserCredential;

/** Storage token from a User Credentials artifact. */
public class SecureStoreTokenResolver implements TokenResolver {

    @Override
    public String resolve(final String credentialName) throws Exception {
        final SecureStoreService service = ITApiFactory.getService(SecureStoreService.class, null);
        if (service == null) {
            throw new KeboolaImportException(0, "Secure store service is not available on this runtime");
        }
        final UserCredential credential = service.getUserCredential(credentialName);
        if (credential == null || credential.getPassword() == null || credential.getPassword().length == 0) {
            throw new KeboolaImportException(0,
                    "Credential '" + credentialName + "' has no password; deploy a User Credentials artifact whose password is the Keboola Storage API token");
        }
        return new String(credential.getPassword());
    }
}
