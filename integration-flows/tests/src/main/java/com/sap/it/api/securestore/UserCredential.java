package com.sap.it.api.securestore;

/** One User Credentials entry: user name and password. */
public class UserCredential {

    private final String username;
    private final char[] password;

    public UserCredential(final String username, final char[] password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public char[] getPassword() {
        return password;
    }
}
