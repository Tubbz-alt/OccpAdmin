package edu.uri.dfcsc.occp.exceptions.parsing;

/**
 * Child of XMLParseException designed to express that the type of what was given did not match the type of what was
 * expected
 */
public class MismatchedExpectedType extends XMLParseException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructor that takes the exception message
     * 
     * @param message - The exception message
     */
    public MismatchedExpectedType(String message) {
        super(message);
    }
}
