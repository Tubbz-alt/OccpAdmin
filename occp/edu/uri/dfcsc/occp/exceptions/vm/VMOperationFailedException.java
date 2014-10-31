package edu.uri.dfcsc.occp.exceptions.vm;

import edu.uri.dfcsc.occp.exceptions.OccpException;

/**
 * Exception class for all operations involving a VM
 */
public class VMOperationFailedException extends OccpException {
    private static final long serialVersionUID = 1L;

    /**
     * With the exception of LOCK, which is VirtualBox specific, the rest of these codes match a specific method call
     * from the OccpHV interface, the implementations of which should use these when throwing an error
     */
    @SuppressWarnings("javadoc")
    public static enum ErrorCode {
        LOCK("Failed acquiring lock on the VM"),
        POWER_OFF("Failed to power off the VM"),
        POWER_ON("Failed to power on the VM"),
        CLONE("Failed to clone the VM"),
        IMPORT("Failed to import the VM"),
        CREATE_VM("Failed to create the VM"),
        ASSIGN_GROUP("Failed to assign the scenario group to the VM"),
        ASSIGN_NETWORK("Failed to assign the network(s) to the VM"),
        BOOT_ORDER("Failed to set the boot order on the VM"),
        ASSIGN_RAM("Failed to change the RAM allocation for the VM"),
        ATTACH_FLOPPY("Failed to attach the floppy to the VM"),
        ATTACH_ISO("Failed to attach the ISO to the VM"),
        CREATE_SNAPSHOT("Failed to create the snapshot of the VM"),
        REVERT_SNAPSHOT("Failed to revert the VM to the snapshot"),
        DELETE_SNAPSHOT("Failed to delete the snapshot of the VM"),
        SHARED_FOLDER("Failed to create the shared folder on the VM"),
        GUEST("Failed acquire a guest session on the VM"),
        TRANSFER_TO("Failed to transfer the file to the VM"),
        TRANSFER_FROM("Failed to retrieve the file from the VM"),
        RUN_COMMAND("Failed to run a command on the VM"),
        DELETE_VM("Failed to delete the VM"),
        EXPORT("Failed to Export the VM");
        private final String msg;

        private ErrorCode(String msg) {
            this.msg = msg;
        }
    };

    private final VMOperationFailedException.ErrorCode code;
    private final String hvName, vmName;

    /**
     * @return The name of the HV that threw this exception
     */
    public String getHVName() {
        return this.hvName;
    }

    /**
     * @return The name of the VM on which the operation was taking place
     */
    public String getVMName() {
        return this.vmName;
    }

    /**
     * @param hvName The name of the HV that threw this exception
     * @param vmName The name of the VM on which the operation was taking place
     * @param code - The operation that failed
     */
    public VMOperationFailedException(String hvName, String vmName, VMOperationFailedException.ErrorCode code) {
        super(code.msg);
        this.code = code;
        this.hvName = hvName;
        this.vmName = vmName;
    }

    /**
     * @param hvName The name of the HV that threw this exception
     * @param vmName The name of the VM on which the operation was taking place
     * @param code The operation that failed
     * @param e The cause of the failure
     */
    public VMOperationFailedException(String hvName, String vmName, VMOperationFailedException.ErrorCode code,
            Throwable e) {
        super(code.msg, e);
        this.code = code;
        this.hvName = hvName;
        this.vmName = vmName;
    }

    /**
     * @param hvName The name of the HV that threw this exception
     * @param vmName The name of the VM on which the operation was taking place
     * @param code The operation that failed
     * @param message Additional information about the failure
     */
    public VMOperationFailedException(String hvName, String vmName, VMOperationFailedException.ErrorCode code,
            String message) {
        super(code.msg + ":" + message);
        this.code = code;
        this.hvName = hvName;
        this.vmName = vmName;
    }

    /**
     * @param hvName The name of the HV that threw this exception
     * @param vmName The name of the VM on which the operation was taking place
     * @param code The operation that failed
     * @param message Additional information about the failure
     * @param e The cause of the failure
     */
    public VMOperationFailedException(String hvName, String vmName, VMOperationFailedException.ErrorCode code,
            String message, Throwable e) {
        super(code.msg + ":" + message, e);
        this.code = code;
        this.hvName = hvName;
        this.vmName = vmName;
    }

    @Override
    public String getMessage() {
        return String.format("HV: %s; VM %s; Code: %s; %s", hvName, vmName, code.name(), super.getMessage());
    }
}
