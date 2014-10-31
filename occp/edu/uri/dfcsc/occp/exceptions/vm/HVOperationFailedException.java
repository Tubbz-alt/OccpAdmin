package edu.uri.dfcsc.occp.exceptions.vm;

import edu.uri.dfcsc.occp.exceptions.OccpException;

/**
 * Exception class for all operations involving a HV
 */
public class HVOperationFailedException extends OccpException {
    private static final long serialVersionUID = 1L;
    private final String hvName;

    @Override
    public String getMessage() {
        return String.format("HV: %s; %s", hvName, super.getMessage());
    }

    /**
     * @param hvName The Hypervisor that which generated the failure
     * @param e The cause of the failure
     */
    public HVOperationFailedException(String hvName, Throwable e) {
        super("General Failure", e);
        this.hvName = hvName;
    }

    /**
     * @param hvName The Hypervisor that which generated the failure
     * @param message A message describing the failure
     */
    public HVOperationFailedException(String hvName, String message) {
        super(message);
        this.hvName = hvName;
    }

    /**
     * @param hvName The Hypervisor that which generated the failure
     * @param message A message describing the failure
     * @param e The cause of the failure
     */
    public HVOperationFailedException(String hvName, String message, Throwable e) {
        super(message, e);
        this.hvName = hvName;
    }
}
