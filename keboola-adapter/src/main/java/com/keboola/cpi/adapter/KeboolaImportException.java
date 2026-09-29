package com.keboola.cpi.adapter;

/** Failed delivery to Keboola Storage. */
public class KeboolaImportException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;

    public KeboolaImportException(final int httpStatus, final String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public KeboolaImportException(final int httpStatus, final String message, final Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
