package com.paycore.history.history;

/** No history for this payment in the caller's tenant: absent, another tenant's, or not yet consumed. */
public class NoHistoryException extends RuntimeException {

    public NoHistoryException() {
        super(null, null, false, false);
    }
}
