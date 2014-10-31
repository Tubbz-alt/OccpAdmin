package edu.uri.dfcsc.occp.exceptions.configmanager;

import edu.uri.dfcsc.occp.exceptions.OccpException;

/**
 * Generic exception for the Configuration Manager. Child classes will give more meaning.
 */
public class ConfigManagerException extends OccpException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructor that takes the causing exception
     * 
     * @param exception - The exception to wrap
     */
    public ConfigManagerException(Throwable exception) {
        super(exception);
    }

    /**
     * Constructor that takes the exception message
     * 
     * @param message - This exception's method
     */
    public ConfigManagerException(String message) {
        super(message);
    }

    /**
     * Constructor that takes both an exception method and the causing exception
     * 
     * @param message - This exception's message
     * @param exception - The exception to wrap
     */
    public ConfigManagerException(String message, Throwable exception) {
        super(message, exception);
    }
}
