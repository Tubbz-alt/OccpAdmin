package edu.uri.dfcsc.occp.exceptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for internal Exceptions
 * Supports adding arbitrary associated data. Also adds appends messages from exception chain into top level message for
 * logging
 * 
 * @author Kevin Bryan (bryank@cs.uri.edu)
 */
public class OccpException extends Exception {
    private static final long serialVersionUID = 1L;
    protected final Map<String, Object> data = new HashMap<>();

    protected String formatData() {
        if (data.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        result.append("\nData:\n");
        for (String key : data.keySet()) {
            result.append("\t" + key + "=[" + data.get(key) + "]\n");
        }
        return result.toString();
    }

    @Override
    public String getMessage() {
        // By default the message doesn't include caused by's message, so we piece it together
        StringBuilder message = new StringBuilder(super.getMessage());
        Throwable e = getCause();
        String lastMessage = message.toString();
        while (e != null) {
            String thisMessage = e.getMessage();
            // Hide messages that have already been shown
            // This tends to happen with some VBoxExceptions
            if (thisMessage != null && !lastMessage.contains(thisMessage)) {
                message.append("\nCaused by: ");
                lastMessage = e.getMessage();
                message.append(lastMessage);
            }
            e = e.getCause();
        }
        return message.toString() + formatData();
    }

    /**
     * Used to add data to an Exception in an easy to parse way
     * 
     * @param key Type of data
     * @param value Data
     * @return this
     */
    public OccpException set(String key, Object value) {
        data.put(key, value);
        return this;
    }

    /**
     * Used to get data from an Exception
     * 
     * @param key Type of data
     * @return Requested data
     */
    public Object get(String key) {
        return data.get(key);
    }

    protected OccpException() {
        super();
    }

    protected OccpException(Throwable e) {
        super(e);
    }

    protected OccpException(String message) {
        super(message);
    }

    protected OccpException(String message, Throwable e) {
        super(message, e);
    }
}
