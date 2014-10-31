package edu.uri.dfcsc.occp.exceptions.vm;

import edu.uri.dfcsc.occp.exceptions.OccpException;

/**
 * Thrown by getVM and getBaseVM
 */
public class VMNotFoundException extends OccpException {
    private static final long serialVersionUID = 1L;

    /**
     * Thrown when a VM is not found
     * 
     * @param vmName - Name of the missing VM
     */
    public VMNotFoundException(String vmName) {
        super("VM Not Found");
        set("name", vmName);
    }
}
