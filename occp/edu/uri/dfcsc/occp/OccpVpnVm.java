/**
 * This class provides an interface to a remote setup / VPN VM 
 */
package edu.uri.dfcsc.occp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import edu.uri.dfcsc.occp.OccpHV.OccpVM;
import edu.uri.dfcsc.occp.exceptions.OccpException;
import edu.uri.dfcsc.occp.exceptions.vm.VMOperationFailedException;
import edu.uri.dfcsc.occp.exceptions.vm.VMOperationFailedException.ErrorCode;

/**
 * @author Kevin Bryan (bryank@cs.uri.edu)
 */
public class OccpVpnVm {
    final OccpVM vm;
    OccpHV hv = null;
    String ip = null;
    private boolean isConnected;
    private final Map<String, Condition> completed_conditions = new HashMap<>();
    private final Map<String, Boolean> completed_transfers = new HashMap<>();
    private final ReentrantLock completionLock = new ReentrantLock();

    OccpVpnVm(OccpHV hv, OccpVM vm, String ip) {
        if (vm == null) {
            throw new IllegalArgumentException();
        }
        this.hv = hv;
        this.vm = vm;
        this.ip = ip;
    }

    /**
     * Ensure that the VM is installed in the Hypervisor, is attached to the correct network, and has a shared folder,
     * if necessary
     * 
     * @param nets - List of networks to connect to
     * @param sharedfolder - Path to host shared folder, or null
     * @return validation successful
     * @throws OccpException
     */
    public boolean verify(List<String> nets, String sharedfolder) throws OccpException {
        hv.assignVMNetworks(this.vm, nets);
        if (sharedfolder != null) {
            hv.createSharedFolder(this.vm);
        }
        return true;
    }

    /**
     * Power on the VM and then wait for the guest service to start
     * 
     * @return true if both were successful
     * @throws OccpException
     */
    public boolean powerOnAndWait() throws OccpException {
        hv.setBootCD(this.vm);
        hv.powerOnVM(this.vm);
        hv.waitForGuestPowerOn(this.vm);
        return true;
    }

    /**
     * @param sourcePath - Local file to send
     * @param destPath - Remote path to file
     * @param executable - Mark the file as executable
     * @throws OccpException
     */
    public void transferFileToVM(String sourcePath, String destPath, boolean executable) throws OccpException {
        hv.transferFileToVM(this.vm, sourcePath, destPath, executable);
    }

    /**
     * Turn off the VM
     * 
     * @throws OccpException
     */
    public void powerOff() throws OccpException {
        hv.powerOffVM(this.vm);
    }

    /**
     * Start openvpn on the VM
     * 
     * @return success
     * @throws OccpException
     */
    public boolean startVPN() throws OccpException {
        /*
         * Need to configure the bridge as well as start openvpn.
         * The bridge is between the openvpn tap device and the second interface on the VPN which is connected to the
         * setup network. The openvpn configuration will add the tap device.
         */
        String[][] cmds = { { "/usr/sbin/brctl", "addbr", "br0" }, { "/usr/sbin/brctl", "addif", "br0", "eth1" },
                { "/usr/sbin/brctl", "setfd", "br0", "4" },
                { "/sbin/ifconfig", "br0", this.ip, "up" }, { "/sbin/ifconfig", "eth1", "up", "promisc" },
                /* Workaround for VBox not using permissions given */
                { "/bin/chmod", "a+x", "/etc/openvpn/up.sh" }, { "/usr/sbin/mount.vboxsf", "importdir", "/mnt" },
                { "/bin/mkdir", "/mnt/" + OccpAdmin.scenarioName },
                { "/usr/sbin/openvpn", "--config", "/etc/openvpn/" + OccpAdmin.setupNetworkName + ".conf", "--daemon" } };
        hv.runCommand(this.vm, cmds[0], true);
        hv.runCommand(this.vm, cmds[1], true);
        hv.runCommand(this.vm, cmds[2], true);
        hv.runCommand(this.vm, cmds[3], true);
        hv.runCommand(this.vm, cmds[4], true);
        hv.runCommand(this.vm, cmds[5], true);
        hv.runCommand(this.vm, cmds[6], true);
        hv.runCommand(this.vm, cmds[7], true);
        hv.runCommand(this.vm, cmds[8], false);
        isConnected = true;
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            // Generally safe to ignore, as this is just to make sure the bridge settles
        }
        return true;
    }

    /**
     * Transfer the local file to the hypervisor's importdir
     * 
     * @param from Local file name
     * @throws OccpException
     */
    public void stageFile(String from) throws OccpException {
        boolean transferSuccess = false;
        /*
         * Only VBox requires transfer to the host; Esxi allows uploads
         */
        if (this.hv.getClass() == OccpVBoxHV.class) {
            try {
                completionLock.lock();
                if (!completed_conditions.containsKey(from)) {
                    completed_conditions.put(from, completionLock.newCondition());
                } else {
                    while (!completed_transfers.containsKey(from)) {
                        try {
                            completed_conditions.get(from).await();
                        } catch (InterruptedException e) {
                            throw new VMOperationFailedException(hv.getName(), vm.getName(), ErrorCode.TRANSFER_TO,
                                    "Transfer interrupted", e);
                        }
                    }
                    // If it has been transferred, say we did it, otherwise try again
                    if (completed_transfers.get(from) == true) {
                        return;
                    }
                }
            } finally {
                completionLock.unlock();
            }

            boolean hasPath = (from.lastIndexOf('/') >= 0);
            String to = from;
            if (hasPath) {
                to = from.substring(from.lastIndexOf('/') + 1);
            }
            try {
                this.hv.transferFileToVM(this.vm, from, "/mnt/" + OccpAdmin.scenarioName + "/" + to, false);
                transferSuccess = true;
            } finally {
                completionLock.lock();
                try {
                    completed_transfers.put(from, transferSuccess);
                    completed_conditions.get(from).signal();
                } finally {
                    completionLock.unlock();
                }
            }
        }
    }

    /**
     * Retrieve a file from the hypervisor's importdir
     * 
     * @param from remote file name
     * @param to local file name
     * @throws OccpException
     */
    public void fetchFile(String from, String to) throws OccpException {
        /*
         * Only VBox requires transfer from the host; Esxi allows downloads
         */
        if (this.hv.getClass() == OccpVBoxHV.class) {
            this.hv.retrieveFileFromVM(this.vm, from, to);
        }
    }

    /**
     * Determines whether or not it is connected
     * 
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return isConnected;
    }
}
