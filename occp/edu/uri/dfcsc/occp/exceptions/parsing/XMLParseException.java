package edu.uri.dfcsc.occp.exceptions.parsing;

import edu.uri.dfcsc.occp.exceptions.OccpException;

/**
 * Base exception that other parse exceptions will extend. You can catch this or explicitly catch a child exception
 * if you can do meaningful recovery.
 */
public class XMLParseException extends OccpException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructor that takes the exception message
     * 
     * @param message - The exception message
     */
    public XMLParseException(final String message) {
        super(message);
    }
}
