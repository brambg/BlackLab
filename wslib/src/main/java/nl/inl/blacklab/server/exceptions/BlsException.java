package nl.inl.blacklab.server.exceptions;

/**
 * Thrown when the requested index was not available or could not be opened
 */
public class BlsException extends RuntimeException {
    
    public static InternalServerError indexVersionMismatch(Throwable e) {
        return new BlsIndexOpenException("This index version cannot be opened with this BlackLab version.", "INTERR_INDEX_VERSION", e);
    }

    /**
     * The HTTP error code to send.
     */
    protected final int httpStatusCode;

    /**
     * A symbolic error code that the client can recognize and show a custom message
     * for.
     */
    protected final String errorCode;

    public BlsException(int httpStatusCode, String errorCode, String msg, Throwable cause) {
        super(msg, cause);
        this.httpStatusCode = httpStatusCode;
        this.errorCode = errorCode;
    }

    public BlsException(int httpCode, String errorCode, String msg) {
        this(httpCode, errorCode, msg, null);
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public String getBlsErrorCode() {
        return errorCode;
    }

}
