package edu.uri.dfcsc.occp.exceptions.parsing;

/**
 * Child of XMLParseException designed to express that something was required was not given.
 */
public class MissingRequired extends XMLParseException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructor that takes the exception message
     * 
     * @param message - The exception message
     */
    public MissingRequired(String message) {
        super(message);
    }
}
