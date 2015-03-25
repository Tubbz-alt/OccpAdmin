package edu.uri.dfcsc.occp;

import java.util.List;
import java.util.Map;

import edu.uri.dfcsc.occp.exceptions.OccpException;
import edu.uri.dfcsc.occp.exceptions.vm.HVOperationFailedException;
import edu.uri.dfcsc.occp.exceptions.vm.VMNotFoundException;

/**
 * Interface to generic Hypervisor An implementation of this interface must supply all methods to have complete
 * functionality
 * 
 * @author Kevin Bryan (bryank@cs.uri.edu)
 */
public interface OccpHV {
    /**
     * @return Return hypervisor name
     */
    public String getName();

    /**
     * Generic VM Handle
     */
    public interface OccpVM {
        /**
         * @return The name of the VM
         */
        String getName();
    }

    /**
     * Parse the CLI args for HV specific parameters
     * * Each hypervisor might have a different set of parameters, so we try to be general
     * * Common values should be shared: user, password
     * 
     * @param args - Args from the command line
     * @return True if required fields exist, False otherwise
     */
    public boolean parseArgs(String[] args);

    /**
     * Connect to the hypervisor
     * * Note that often administrator level privileges are required to perform the necessary configuration actions
     * 
     * @return True if connected, False or Exception on error
     */
    public boolean connect();

    /**
     * Close the connection to the hypervisor
     */
    public void disconnect();

    /**
     * Query to see if a VM has a snapshot with a given name
     * 
     * @param vm - as retrieved by getVM()
     * @param snapshotName - name of the snapshot
     * @return True if it exists, otherwise false
     * @throws OccpException
     */
    public boolean hasSnapshot(OccpVM vm, String snapshotName) throws OccpException;

    /**
     * Create a snapshot with a given name
     * 
     * @param vm - as retrieved by getVM()
     * @param snapshotName - name of the snapshot
     * @throws OccpException
     */
    public void createSnapshot(OccpVM vm, String snapshotName) throws OccpException;

    /**
     * Return a handle to a VM with a given name
     * 
     * @param vmName - name of the VM to retrieve
     * @return A handle to the VM, or null if it is not found
     * @throws VMNotFoundException
     * @throws HVOperationFailedException
     */
    public OccpVM getVM(String vmName) throws VMNotFoundException, HVOperationFailedException;

    /**
     * Return a handle to a VM with a given name that isn't part of
     * any scenario
     * 
     * @param vmName - name of the VM to retrieve
     * @return A handle to the VM, or null if it is not found
     * @throws VMNotFoundException
     * @throws HVOperationFailedException
     */
    public OccpVM getBaseVM(String vmName) throws VMNotFoundException, HVOperationFailedException;

    /**
     * Revert a given VM back to a given snapshot name
     * 
     * @param vm - as retrieved by getVM()
     * @param snapshotName - name of the snapshot
     * @throws OccpException
     */
    public void revertToSnapshot(OccpVM vm, String snapshotName) throws OccpException;

    /**
     * Clone a given VM
     * 
     * @param vm - as retrieved by getVM()
     * @param cloneName - name of new VM
     * @param snapshotBase - Name of the snapshot to use as base, if possible
     * @throws OccpException
     */
    public void cloneVM(OccpVM vm, String cloneName, String snapshotBase) throws OccpException;

    /**
     * Import a VM from an OVF/OVA file
     * 
     * @param vmName Name of the new VM
     * @param fileName Name of the file to import
     * @throws OccpException
     */
    public void importVM(String vmName, String fileName) throws OccpException;

    /**
     * Export a VM to an OVA file
     * 
     * @param vm - Handle to the VM
     * @param fileName Name of the file to import
     * @param exportName Name of the file to create
     * @throws OccpException
     */
    public void exportVM(OccpVM vm, String fileName, String exportName) throws OccpException;

    /**
     * Retrieve the MAC address of the VM (first one)
     * 
     * @param vm - as retrieved by getVM()
     * @param iFaceNumber - Which interface to retrieve
     * @return String of the first MAC address
     * @throws OccpException
     */
    public String getVMMac(OccpVM vm, int iFaceNumber) throws OccpException;

    /**
     * Power on a VM
     * 
     * @param vm - as retrieved by getVM()
     * @throws OccpException
     */
    public void powerOnVM(OccpVM vm) throws OccpException;

    /**
     * Check power state of a VM
     * 
     * @param vm - as retrieved by getVM()
     * @return True if the machine started, False otherwise
     */
    public boolean isVMOn(OccpVM vm);

    /**
     * Power off a VM
     * 
     * @param vm - as retrieved by getVM()
     * @throws OccpException
     */
    public void powerOffVM(OccpVM vm) throws OccpException;

    /**
     * Determine the existence of a network
     * 
     * @param netName - Name of the network
     * @return True if the network exists, False otherwise
     */
    public boolean networkExists(String netName);

    /**
     * Create a network
     * 
     * @param netName - Name of the network
     * @throws OccpException
     */
    public void createNetwork(String netName) throws OccpException;

    /**
     * Check if a VM is connected to a given network
     * 
     * @param vm - as retrieved by getVM()
     * @param netName - Name of the network
     * @return - success/failure
     * @throws OccpException
     */
    public boolean isVMOnNetwork(OccpVM vm, String netName) throws OccpException;

    /**
     * Assign the networks required for this VM (they must exist)
     * 
     * @param vm - as retrieved by getVM()
     * @param networks - Names of the Networks
     * @throws OccpException
     */
    public void assignVMNetworks(OccpVM vm, List<String> networks) throws OccpException;

    /**
     * Upload a floppy image to the hypervisor and attach it to a machine Currently expected to be used only for the
     * router machine
     * 
     * @param vm - as retrieved by getVM()
     * @param filename - path to floppy image
     * @throws OccpException
     */
    public void attachFloppy(OccpVM vm, String filename) throws OccpException;

    /**
     * Ensure the given VM will boot off the CD
     * 
     * @param vm - as retrieved by getVM()
     * @throws OccpException
     */
    public void setBootCD(OccpVM vm) throws OccpException;

    /**
     * Add the connection parameters to the default connection info file
     * 
     * @return - Key/Value pairs for cached connection parameters
     */
    public Map<String, String> getSaveParameters();

    /**
     * Create a shared folder, if it makes sense for that HV
     * 
     * @param vm - vm to modify
     * @throws OccpException
     */
    public void createSharedFolder(OccpVM vm) throws OccpException;

    /**
     * Wait for guest service to start inside VM (Only for setup/VPN VM)
     * 
     * @param vm - vm to wait for
     * @throws OccpException
     */
    public void waitForGuestPowerOn(OccpVM vm) throws OccpException;

    /**
     * Transfer a file from the AdminVM to the remote VM
     * Requires guest utilities to be installed
     * 
     * @param vm - Destination VM
     * @param sourcePath - Local path
     * @param destPath - Remote path
     * @param executable - Mark the file as executable
     * @throws OccpException
     */
    public void transferFileToVM(OccpVM vm, String sourcePath, String destPath, boolean executable)
            throws OccpException;

    /**
     * Runs a command on a VM using the guest utilities
     * 
     * @param vm - vm on which to run
     * @param cmd - cmd to run, first element must be full path
     * @param waitForIt - whether or not to wait for the process to finish
     * @throws OccpException
     */
    public void runCommand(OccpVM vm, String[] cmd, boolean waitForIt) throws OccpException;

    /**
     * If isLocal is true, set this hypervisor as local (AdminVM's eth1 is on the setupNetworkName on this hypervisor).
     * This will prevent the program from starting the Setup VPN VM on this host during setup.
     * 
     * @param isLocal - True if AdminVM is running under this HV, false otherwise
     */
    public void setLocal(boolean isLocal);

    /**
     * @return True if this HV is the one that the AdminVM is running on.
     */
    public boolean getLocal();

    /**
     * Ensure this VM has the requested amount of RAM (in MB)
     * 
     * @param vm - as retrieved by getVM()
     * @param ram -number of MB of RAM to allocate to VM
     * @throws OccpException
     */
    public void assignVMRam(OccpVM vm, int ram) throws OccpException;

    /**
     * Retrieve a file from a VM (used for collecting exported files).
     * 
     * @param vm - VM containing the file (must have guest utilities)
     * @param from - Name of file (relative to importdir)
     * @param to - Local file location
     * @throws OccpException
     */
    public void retrieveFileFromVM(OccpVM vm, String from, String to) throws OccpException;

    /**
     * Remove a VM that isn't needed anymore
     * 
     * @param vm - Handle to the VM
     * @throws OccpException
     */
    public void deleteVM(OccpVM vm) throws OccpException;

    /**
     * Remove a snapshot from a VM
     *
     * @param vm - Handle to the VM
     * @param snapshotName - name of the snapshot to remove
     * @throws OccpException
     */
    public void deleteSnapshot(OccpVM vm, String snapshotName) throws OccpException;

    /**
     * Create a VM using the supposed ISO file as the boot device
     *
     * @param vmName - Name of the machine
     * @param isoFilename - Name of the .iso file (in working directory)
     * @return Handle to the new VM
     * @throws OccpException
     */
    public OccpVM createVMwithISO(String vmName, String isoFilename) throws OccpException;
}
