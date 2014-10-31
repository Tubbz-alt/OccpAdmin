package edu.uri.dfcsc.occp.exceptions.configmanager;

/**
 * Indicates that there is an unrecoverable failure with some requested action for the Configuration Manager.
 */
public class ConfigManagerPermanentFailureException extends ConfigManagerException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructor that takes the causing exception
     * 
     * @param exception - The exception to wrap
     */
    public ConfigManagerPermanentFailureException(Throwable exception) {
        super(exception);
    }

    /**
     * Constructor that takes the exception message
     * 
     * @param message - This exception's method
     */
    public ConfigManagerPermanentFailureException(String message) {
        super(message);
    }

    /**
     * Constructor that takes both an exception method and the causing exception
     * 
     * @param message - This exception's message
     * @param exception - The exception to wrap
     */
    public ConfigManagerPermanentFailureException(String message, Throwable exception) {
        super(message, exception);
    }
}
