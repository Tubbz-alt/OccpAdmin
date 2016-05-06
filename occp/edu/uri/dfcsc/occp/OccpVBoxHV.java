package edu.uri.dfcsc.occp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.virtualbox_5_0.*;

import edu.uri.dfcsc.occp.exceptions.OccpException;
import edu.uri.dfcsc.occp.exceptions.vm.HVOperationFailedException;
import edu.uri.dfcsc.occp.exceptions.vm.VMNotFoundException;
import edu.uri.dfcsc.occp.exceptions.vm.VMOperationFailedException;
import edu.uri.dfcsc.occp.exceptions.vm.VMOperationFailedException.ErrorCode;

/**
 * Virtualbox specific implementation of the Occp Hypervisor interface
 * <ul>
 * <li>uses vboxjws.jar
 * <li>borrows liberally from Virtualbox's sample code
 * </ul>
 * 
 * @author Adam Sowden (a.sowden10000@gmail.com)
 * @author Kevin Bryan (bryank@cs.uri.edu)
 */
public class OccpVBoxHV implements OccpHV {
    private static Logger logger = Logger.getLogger(OccpVBoxHV.class.getName());
    String name;
    String userName;
    String password = null;
    String url;
    String importDir;
    private int jobs = 1;
    IVirtualBox vbox;
    VirtualBoxManager mgr = VirtualBoxManager.createInstance(null);
    private boolean isLocal = false;
    private Thread keepAlive;
    private final Map<String, OccpVBoxVM> cachedVMs = new HashMap<>();
    private final String groupName = "/occp-" + OccpAdmin.scenarioName;

    /**
     * Wrapper class for handle to a Virtualbox VM
     * 
     * @author Adam Sowden (a.sowden10000@gmail.com)
     */

    public static class OccpVBoxVM implements OccpHV.OccpVM {
        IMachine machine;
        VirtualBoxManager vmMgr;
        ISession session;
        String name;

        public ISession getSession() {
            if (session == null) {
                session = vmMgr.getSessionObject();
            }
            return session;
        }
        @Override
        public String getName() {
            return this.name;
        }
    }

    /**
     * If {@code cache} is {@code null}, then {@code parseArgs }must be called
     * before {@code connect}
     * 
     * @param name The name of this hypervisor
     * @param cache Connection information, if available.
     */
    public OccpVBoxHV(String name, Map<String, String> cache) {
        this.name = name;
        if (cache != null) {
            url = cache.get("url");
            userName = cache.get("username");
            password = cache.get("password");
            importDir = cache.get("importdir");
            if (cache.get("jobs") != null) {
                jobs = Integer.parseInt(cache.get("jobs"));
            }
        }
    }

    @Override
    public boolean parseArgs(String[] args) {
        int ai = 0;
        String param = "";
        String val = "";
        while (ai < args.length) {
            param = args[ai].trim();
            if (ai + 1 < args.length) {
                val = args[ai + 1].trim();
            }
            if (param.equalsIgnoreCase("--url") && !val.startsWith("--") && !val.isEmpty()) {
                url = val;
            } else if (param.equalsIgnoreCase("--username") && !val.startsWith("--") && !val.isEmpty()) {
                userName = val;
            } else if (param.equalsIgnoreCase("--password") && !val.startsWith("--")) {
                // Allows user to specify password as "" for blank password
                password = val;
            } else if (param.equalsIgnoreCase("--importdir") && !val.startsWith("--") && !val.isEmpty()) {
                importDir = val;
            } else if (param.equalsIgnoreCase("--jobs") && !val.startsWith("--") && !val.isEmpty()) {
                jobs = Integer.parseInt(val);
            } else {
                --ai; // Ignore this unknown parameter
            }
            val = "";
            ai += 2;
        }
        if (url == null) {
            throw new IllegalArgumentException("Expected --url argument.");
        }
        if (importDir == null) {
            throw new IllegalArgumentException("Expected --importdir argument.");
        }
        return true;
    }

    @Override
    public Map<String, String> getSaveParameters() {
        Map<String, String> params = new HashMap<String, String>();
        params.put("hypervisor", "vbox"); // required
        params.put("url", url);
        params.put("username", userName);
        if (password != null) {
            params.put("password", password);
        }
        params.put("importdir", importDir);
        params.put("jobs", "" + jobs);
        return params;
    }

    @Override
    public void setLocal(boolean isLocal) {
        this.isLocal = isLocal;
    }

    @Override
    public boolean getLocal() {
        return this.isLocal;
    }

    @Override
    public int getJobs() {
        return this.jobs;
    }

    @Override
    public boolean connect() {
        try {
            if (password == null) {
                char[] promptedPassword = OccpAdmin.getPassword(this.userName + "@" + this.url);
                if (promptedPassword == null) {
                    return false;
                }
                password = new String(promptedPassword);
            }
            mgr.connect(url, userName, password);
            vbox = mgr.getVBox();
            if (vbox == null) {
                return false;
            }
            logger.finest("Logged onto " + this.name + " running API version " + vbox.getAPIVersion());
            keepAlive = new Thread(new KeepAlive());
            keepAlive.start();
        } catch (Exception e) {
            if (e.getCause().getClass() == IllegalArgumentException.class) {
                logger.severe("Authentication failure connecting to " + this.url);
            } else {
                logger.log(Level.SEVERE, "Connection failure connecting to " + this.url, e);
            }
            return false;
        }
        return true;
    }

    @Override
    public void disconnect() {
        if (vbox != null) {
            keepAlive.interrupt();
            mgr.disconnect();
            // Also close other outstanding connections
            for (OccpVBoxVM vm : cachedVMs.values()) {
                if (vm.vmMgr != mgr) {
                    vm.vmMgr.disconnect();
                }
            }
            logger.finest("Logged off " + this.name);
        }
        mgr = null;
    }

    @Override
    public OccpVM getVM(String vmName) throws VMNotFoundException {
        OccpVBoxVM handle = cachedVMs.get(vmName);
        if (handle != null) {
            return handle;
        }
        List<IMachine> machines = vbox.getMachines();
        for (IMachine machine : machines) {
            try {
            if (machine.getName().equals(vmName)) {
                if (machine.getGroups().contains(groupName)) {
                    handle = new OccpVBoxVM();
                    handle.machine = machine;
                    handle.vmMgr = mgr;
                    handle.name = vmName;
                    cachedVMs.put(vmName, handle);
                }
            }
            } catch (VBoxException e) {
                if (e.getResultCode() == /* E_ACCESSDENIED */0x80070005) {
                    continue;
                }
                throw e;
            }
        }
        if (handle != null) {
            return handle;
        }
        throw new VMNotFoundException(vmName);
    }

    @Override
    public OccpVM getBaseVM(String vmName) throws VMNotFoundException {
        OccpVBoxVM handle = cachedVMs.get(vmName);
        if (handle != null) {
            return handle;
        }
        handle = new OccpVBoxVM();
        try {
            handle.machine = vbox.findMachine(vmName);
            handle.name = vmName;
            handle.vmMgr = mgr;
            cachedVMs.put(vmName, handle);
            return handle;
        } catch (VBoxException e) {
            // Check for (VBOX_E_OBJECT_NOT_FOUND)
            if (e.getResultCode() != 0x80bb0001) {
                logger.log(Level.SEVERE, "Unexpected failure looking for: " + vmName, e);
            }
            throw new VMNotFoundException(vmName);
        }
    }

    @Override
    public void powerOnVM(OccpVM vm) throws OccpException {
        ISession oSession = null;
        IMachine oMachine = null;

        try {
            /*
             * VirtualBox will not release a lock for a running VM, so we have to
             * reconnect for each PowerOn operation
             */
            VirtualBoxManager mgr_ = VirtualBoxManager.createInstance(null);
            mgr_.connect(url, userName, password);
            // Although it would be nice if we could blank this, we may need more
            // than one connection
            IVirtualBox vbox_ = mgr_.getVBox();
            if (vbox_ == null) {
                throw new HVOperationFailedException(name, "Failed to get a handle to VirtualBox");
            }
            oSession = mgr_.getSessionObject();
            ((OccpVBoxVM) vm).session = oSession;
            oMachine = ((OccpVBoxVM) vm).machine;
            String sessionType = "gui";
            // launch implies lock
            logger.finest("Launching VM " + vm.getName());
            IProgress oProgress = oMachine.launchVMProcess(oSession, sessionType, null);
            oProgress.waitForCompletion(-1);

            long rc = oProgress.getResultCode();
            if (rc != 0) {
                IVirtualBoxErrorInfo err = oProgress.getErrorInfo();
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.POWER_ON, getVBoxErrorMessage(err))
                        .set("virtualbox code", rc);
            }
            // If we have powered it on before, close the old handle
            if (((OccpVBoxVM) vm).vmMgr != mgr) {
                ((OccpVBoxVM) vm).vmMgr.disconnect();
            }
            // Use this manager while the VM is turned on
            ((OccpVBoxVM) vm).vmMgr = mgr_;
        } catch (VBoxException e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.POWER_ON, e);
        } finally {
            unlockMachine(oSession, oMachine);
        }
    }

    @Override
    public void powerOffVM(OccpVM vm) throws OccpException {
        ISession oSession = null;
        IMachine oMachine = null;
        try {
            oMachine = ((OccpVBoxVM) vm).machine;
            MachineState state = oMachine.getState();
            if (state == MachineState.PoweredOff || state == MachineState.Aborted) {
                return;
            }
            oSession = ((OccpVBoxVM) vm).getSession();
            lockMachine(oSession, oMachine, LockType.Shared);
            IConsole oConsole = oSession.getConsole();
            if (state == MachineState.Saved) {
                if (OccpAdmin.force) {
                    logger.finest("Discarding Saved state of " + vm.getName());
                    oSession.getMachine().discardSavedState(true);
                    return;
                }
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.POWER_OFF,
                        "VM Has saved state, but --force not specified");
            }
            logger.finest("Powering off " + vm.getName());
            IProgress oProgress = oConsole.powerDown();
            oProgress.waitForCompletion(-1);
            long rc = oProgress.getResultCode();
            if (rc != 0) {
                IVirtualBoxErrorInfo err = oProgress.getErrorInfo();
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.POWER_OFF, getVBoxErrorMessage(err))
                        .set("virtualbox code", rc);
            }
        } catch (VBoxException e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.POWER_OFF, e);
        } finally {
            unlockMachine(oSession, oMachine);
        }
    }

    @Override
    public boolean isVMOn(OccpVM vm) {
        IMachine oMachine = ((OccpVBoxVM) vm).machine;
        MachineState state = oMachine.getState();
        if (state != MachineState.PoweredOff) {
            return true;
        }
        return false;
    }

    @Override
    public boolean hasSnapshot(OccpVM vm, String snapshotName) throws OccpException {
        IMachine oMachine = null;
        try {
            oMachine = ((OccpVBoxVM) vm).machine;
            if (oMachine.findSnapshot(snapshotName) == null) {
                return false;
            }
        } catch (VBoxException e) {
            if (e.getResultCode() != 0x80bb0001 /* No snapshots */) {
                throw new HVOperationFailedException(name, "Unexpected failure", e).set("snapshot", snapshotName);
            }
            return false;
        }
        return true;
    }

    @Override
    public void createSnapshot(OccpVM vm, String snapshotName) throws OccpException {
        ISession oSession = null;
        IMachine oMachine = null;
        try {
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            oMachine = ((OccpVBoxVM) vm).machine;
            lockMachine(oSession, oMachine, LockType.Write);
            Holder<String> uuid = new Holder<>();
            IProgress oProgress = oSession.getMachine().takeSnapshot(snapshotName, "", true, uuid);
            oProgress.waitForCompletion(-1);
            long rc = oProgress.getResultCode();
            if (rc != 0) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.CREATE_SNAPSHOT,
                        getVBoxErrorMessage(oProgress.getErrorInfo()))
                        .set("virtualbox code", oProgress.getResultCode());
            }
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.CREATE_SNAPSHOT, e);
        } finally {
            unlockMachine(oSession, oMachine);
        }
    }

    @Override
    public void cloneVM(OccpVM vm, String cloneName, String snapshotBase) throws OccpException {
        IMachine oMachine = null, target = null;
        try {
            oMachine = ((OccpVBoxVM) vm).machine;
            // TODO: What is the best logic for choosing the state to use
            ISnapshot snapshot = null;
            synchronized (this) {
                try {
                    snapshot = oMachine.findSnapshot(snapshotBase);
                } catch (VBoxException e) {
                    // No snapshots
                    if (e.getResultCode() == 0x80bb0001) {
                        createSnapshot(vm, snapshotBase);
                        snapshot = oMachine.findSnapshot(snapshotBase);
                    } else {
                        throw e;
                    }
                }
            }
            IMachine sMachine = snapshot.getMachine();
            target = vbox.createMachine(null, cloneName, null, sMachine.getOSTypeId(), null);
            List<CloneOptions> opts = new ArrayList<CloneOptions>();
            opts.add(CloneOptions.Link);
            IProgress oProgress = sMachine.cloneTo(target, CloneMode.MachineState, opts);
            oProgress.waitForCompletion(-1);
            long rc = oProgress.getResultCode();
            if (rc != 0) {
                throw new VMOperationFailedException(name, cloneName, ErrorCode.CLONE,
                        getVBoxErrorMessage(oProgress.getErrorInfo())).set("virtualbox code", rc);
            }
            vbox.registerMachine(target);

            OccpVBoxVM newVM = new OccpVBoxVM();
            newVM.machine = target;
            newVM.vmMgr = mgr;
            newVM.name = cloneName;
            assignVMGroup(newVM);
        } catch (VBoxException e) {
            throw new VMOperationFailedException(name, cloneName, ErrorCode.CLONE, e).set("original", vm.getName())
                    .set("clone", cloneName);
        }
    }

    @Override
    public String getVMMac(OccpVM vm, int iFaceNumber) {
        IMachine oMachine = null;
        try {
            oMachine = ((OccpVBoxVM) vm).machine;
            INetworkAdapter oNetworkAdapter = oMachine.getNetworkAdapter((long) iFaceNumber);
            String address = oNetworkAdapter.getMACAddress();
            StringBuilder macAddress = new StringBuilder(18);
            macAddress.append(address);
            macAddress.insert(2, ":");
            macAddress.insert(5, ":");
            macAddress.insert(8, ":");
            macAddress.insert(11, ":");
            macAddress.insert(14, ":");
            return macAddress.toString();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error retrieving MAC address for " + vm.getName() + " card " + iFaceNumber, e);
            return null;
        }
    }

    @Override
    public boolean networkExists(String netName) {
        // Virtualbox will create them automatically.
        return true;
    }

    @Override
    public void createNetwork(String netName) {
        // created by setting the name in the VM
    }

    @Override
    public boolean isVMOnNetwork(OccpVM vm, String netName) {
        IMachine oMachine = null;
        try {
            ISystemProperties props = vbox.getSystemProperties();
            oMachine = ((OccpVBoxVM) vm).machine;
            Long maxAdapters = props.getMaxNetworkAdapters(ChipsetType.PIIX3);
            for (long i = 0; i < maxAdapters; ++i) {
                INetworkAdapter oNetworkAdapter = oMachine.getNetworkAdapter(i);
                if (oNetworkAdapter.getAttachmentType() == NetworkAttachmentType.Internal) {
                    if (netName.equals(oNetworkAdapter.getInternalNetwork())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error checking if the VM \"" + vm.getName() + "\" is on " + netName, e);
            return false;
        }
        return false;
    }

    @Override
    public void assignVMNetworks(OccpVM vm, List<String> networks) throws OccpException {
        IMachine oMachine = null;
        ISession oSession = null;
        try {
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            oMachine = ((OccpVBoxVM) vm).machine;
            lockMachine(oSession, oMachine, LockType.Write);
            long i = 0;
            String netName;
            boolean changed = false;
            ISystemProperties props = vbox.getSystemProperties();
            for (i = 0; i < props.getMaxNetworkAdapters(oMachine.getChipsetType()); ++i) {
                INetworkAdapter oNetworkAdapter = oSession.getMachine().getNetworkAdapter(i);
                if (i < networks.size()) {
                    netName = networks.get((int) i);
                    // We aren't to reconfigure adapters set to null, except to make
                    // sure they are enabled

                    if (netName != null) {
                        if (!oNetworkAdapter.getAttachmentType().equals(NetworkAttachmentType.Internal)) {
                            oNetworkAdapter.setAttachmentType(NetworkAttachmentType.Internal);
                            changed = true;
                        }
                        if (!oNetworkAdapter.getInternalNetwork().equals(netName)) {
                            oNetworkAdapter.setInternalNetwork(netName);
                            changed = true;
                        }
                        if (oNetworkAdapter.getPromiscModePolicy() != NetworkAdapterPromiscModePolicy.AllowAll) {
                            oNetworkAdapter.setPromiscModePolicy(NetworkAdapterPromiscModePolicy.AllowAll);
                            changed = true;
                        }
                    }
                    if (!oNetworkAdapter.getEnabled()) {
                        oNetworkAdapter.setEnabled(true);
                        changed = true;
                    }
                    // Ensure we use the best and same networking on all cards
                    if (oNetworkAdapter.getAdapterType() != NetworkAdapterType.Virtio) {
                        oNetworkAdapter.setAdapterType(NetworkAdapterType.Virtio);
                        changed = true;
                    }
                } else if (oNetworkAdapter.getEnabled()) {
                    // Disable all other cards
                    oNetworkAdapter.setEnabled(false);
                    changed = true;
                }
            }
            if (changed) {
                oSession.getMachine().saveSettings();
            }
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.ASSIGN_NETWORK, e);
        } finally {
            unlockMachine(oSession, oMachine);
        }
    }

    @Override
    public void revertToSnapshot(OccpVM vm, String snapshotName) throws OccpException {
        ISession oSession = null;
        IMachine oMachine = null;
        try {
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            oMachine = ((OccpVBoxVM) vm).machine;
            lockMachine(oSession, oMachine, LockType.Write);
            oMachine = oSession.getMachine();
            ISnapshot snapshot = oMachine.findSnapshot(snapshotName);
            if (snapshot == null) {
                throw new IllegalArgumentException("Snapshot does not exist");
            }
            IProgress oProgress = oSession.getMachine().restoreSnapshot(snapshot);
            oProgress.waitForCompletion(-1);
            long rc = oProgress.getResultCode();
            if (rc != 0) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.REVERT_SNAPSHOT,
                        getVBoxErrorMessage(oProgress.getErrorInfo())).set("virtualbox code", rc);
            }
        } catch (VBoxException | IllegalArgumentException e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.REVERT_SNAPSHOT, e).set("snapshot",
                    snapshotName);
        } finally {
            unlockMachine(oSession, oMachine);
        }

    }

    @Override
    public void attachFloppy(OccpVM vm, String filename) throws OccpException {
        ISession oSession = null;
        IMachine oMachine = null;
        try {
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            oMachine = ((OccpVBoxVM) vm).machine;
            lockMachine(oSession, oMachine, LockType.Write);
            List<IStorageController> controllers = oSession.getMachine().getStorageControllers();
            boolean found = false;
            String floppyControllerName = "Floppy device 0";
            for (IStorageController c : controllers) {
                if (c.getBus() == StorageBus.Floppy) {
                    floppyControllerName = c.getName();
                    found = true;
                }
            }
            IMachine rwMachine = oSession.getMachine();
            // If for some reason we couldn't find the floppy controller for
            // this machine, add one
            if (!found) {
                rwMachine.addStorageController(floppyControllerName, StorageBus.Floppy);
            }
            // Remove the old one to avoid build-up
            try {
                if (rwMachine.getMediumAttachment(floppyControllerName, 0, 0) != null) {
                    rwMachine.unmountMedium(floppyControllerName, 0, 0, true);
                }
            } catch (VBoxException e) {
                if (e.getResultCode() != 0x80bb0001) {
                    // Expected if it doesn't exist
                    logger.log(Level.WARNING, "Could not query floppy controller", e);
                } else {
                    rwMachine.attachDeviceWithoutMedium(floppyControllerName, 0, 0, DeviceType.Floppy);
                }
            }

            IMedium oMedium = vbox.openMedium(this.importDir + "/" + OccpAdmin.scenarioName + "/" + filename,
                    DeviceType.Floppy, AccessMode.ReadWrite, true);
            rwMachine.mountMedium(floppyControllerName, 0, 0, oMedium, true);
            rwMachine.saveSettings();
            found = true;

        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.ATTACH_FLOPPY, e).set("filename",
                    filename);
        } finally {
            unlockMachine(oSession, oMachine);
        }
    }

    private boolean assignVMGroup(OccpVM vm) throws OccpException {
        IMachine oMachine = null, rwMachine = null;
        ISession oSession = null;
        try {
            oMachine = ((OccpVBoxVM) vm).machine;
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            lockMachine(oSession, oMachine, LockType.Write);
            rwMachine = oSession.getMachine();
            List<String> groupList = new ArrayList<>();
            groupList.add(groupName);
            rwMachine.setGroups(groupList);

            boolean noError = true;
            int retries = 0;
            while (noError) {
                try {
                    rwMachine.saveSettings();
                    return true;
                } catch (VBoxException e) {
                    // Rename directory error happens when another VBox thread has the vmdk open, try again
                    if (e.getResultCode() == 0x80004005 && e.getMessage().contains("VERR_ACCESS_DENIED")) {
                        Thread.sleep(500);
                        ++retries;
                        logger.finest("Retrying VM group set: " + retries + "/10");
                        if (retries < 10) {
                            continue;
                        }
                    }
                    throw e;
                }
            }
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.ASSIGN_GROUP, e).set("group", groupName);
        } finally {
            if (rwMachine != null) {
                unlockMachine(oSession, rwMachine);
            }
        }
        return false;
    }

    @Override
    public void setBootCD(OccpVM vm) throws OccpException {
        IMachine oMachine = null;
        ISession oSession = null;
        try {
            oMachine = ((OccpVBoxVM) vm).machine;
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            lockMachine(oSession, oMachine, LockType.Write);
            oSession.getMachine().setBootOrder(1L, DeviceType.DVD);
            oSession.getMachine().saveSettings();
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.BOOT_ORDER, e);
        } finally {
            unlockMachine(oSession, oMachine);
        }
    }

    @Override
    public void importVM(String vmName, String fileName) throws OccpException {
        // Since importApp doesn't give a list of new VMs, memorize which ones exist
        // so we can find the new one.
        List<String> oldUUIDs = new ArrayList<>();
        List<IMachine> machines = vbox.getMachines();
        for (IMachine machine : machines) {
            if (machine.getName().equals(vmName)) {
                oldUUIDs.add(machine.getId());
            }
        }
        IAppliance app = vbox.createAppliance();
        Path ovfPath = Paths.get(this.importDir, OccpAdmin.scenarioName, fileName);
        IProgress prog = app.read(ovfPath.toString());
        prog.waitForCompletion(-1);
        long rc = prog.getResultCode();
        if (rc != 0) {
            throw new VMOperationFailedException(name, vmName, ErrorCode.IMPORT,
                    getVBoxErrorMessage(prog.getErrorInfo())).set("virtualbox code", rc);
        }
        app.interpret();
        List<IVirtualSystemDescription> descs = app.getVirtualSystemDescriptions();
        if (descs.size() > 1) {
            throw new VMOperationFailedException(name, vmName, ErrorCode.IMPORT, "OVA Contains more than one machine");
        }
        List<String> warnings = app.getWarnings();
        if (!warnings.isEmpty()) {
            logger.warning("Warnings" + app.getWarnings());
        }

        for (IVirtualSystemDescription desc : descs) {
            logger.finest("items: " + desc.getCount());
            Holder<List<VirtualSystemDescriptionType>> atypes = new Holder<List<VirtualSystemDescriptionType>>();
            Holder<List<String>> arefs = new Holder<List<String>>();
            Holder<List<String>> aOvfVals = new Holder<List<String>>();
            Holder<List<String>> aVBoxValues = new Holder<List<String>>();
            Holder<List<String>> aExtraConfigValues = new Holder<List<String>>();
            desc.getDescription(atypes, arefs, aOvfVals, aVBoxValues, aExtraConfigValues);
            Long items = desc.getCount();
            List<Boolean> enable = new ArrayList<Boolean>(items.intValue());
            for (int x = 0; x < items; ++x) {
                enable.add(x, true);
                logger.finest("item(" + x + "):");
                if (atypes.value.size() == 0) {
                    continue;
                }
                logger.finest("type:" + atypes.value.get(x).toString());
                logger.finest("ref:" + arefs.value.get(x));
                logger.finest("ovf:" + aOvfVals.value.get(x));
                logger.finest("recommend: " + aVBoxValues.value.get(x));
                logger.finest("extra:" + aExtraConfigValues.value.get(x));
                if (atypes.value.get(x).toString().equalsIgnoreCase("HardDiskImage")) {
                    // Figure out where to put it
                    ISystemProperties sysprops = vbox.getSystemProperties();
                    String base = sysprops.getDefaultMachineFolder();
                    String dirSep = "/";
                    if (base.contains("\\")) {
                        dirSep = "\\";
                    }
                    // Use the filename from the ova in case there is more than one
                    String fullpath = aVBoxValues.value.get(x);
                    String filename = fullpath.substring(fullpath.lastIndexOf(dirSep) + 1);
                    // Use the name of the VM as the base directory
                    String vmdkpath = base + dirSep + vmName + dirSep + filename;

                    aVBoxValues.value.set(x, vmdkpath);
                    logger.finest("changed: " + vmdkpath);
                }
                if (atypes.value.get(x).toString().equalsIgnoreCase("Name")) {
                    aVBoxValues.value.set(x, vmName);
                    logger.finest("changed: " + vmName);
                }
                // Force imports to always use internal
                String extra = aExtraConfigValues.value.get(x);
                if (extra.contains("type=Bridged")) {
                    extra = extra.replace("type=Bridged", "type=Internal");
                    aExtraConfigValues.value.set(x, extra);
                    logger.finest("changed: " + aExtraConfigValues.value.get(x));
                }
            }
            desc.setFinalValues(enable, aVBoxValues.value, aExtraConfigValues.value);
        }
        IProgress importprog = app.importMachines(null);
        importprog.waitForCompletion(-1);
        rc = importprog.getResultCode();
        if (rc != 0) {
            IVirtualBoxErrorInfo err = importprog.getErrorInfo();
            throw new VMOperationFailedException(name, vmName, ErrorCode.IMPORT, getVBoxErrorMessage(err)).set(
                    "virtualbox code", rc);
        }
        machines = vbox.getMachines();
        for (IMachine machine : machines) {
            if (machine.getName().equals(vmName)) {
                if (!oldUUIDs.contains(machine.getId())) {
                    // machine is the new machine
                    OccpVBoxVM newVM = new OccpVBoxVM();
                    newVM.machine = machine;
                    newVM.vmMgr = mgr;
                    newVM.name = vmName;
                    assignVMGroup(newVM);
                    break;
                }
            }
        }
    }

    private synchronized boolean lockMachine(ISession oSession, IMachine oMachine, LockType type)
            throws OccpException {
        int tries = 0;
        logger.finest("lockMachine called from: " + Thread.currentThread().getStackTrace()[2] + " on thread "
                + Thread.currentThread().getName());
        IEventSource es = null;
        IEventListener listener = null;
        boolean haveLock = false;
        IEvent ev = null;
        try {
            do {
                try {
                    logger.finest("Locking the VM \"" + oMachine.getName() + "\" Using session" + oSession.hashCode()
                            + " try "
                            + tries);
                    if (oSession.getState() != SessionState.Locked) {
                        ++tries;
                        oMachine.lockMachine(oSession, type);
                        haveLock = true;
                    } else {
                        // Only do this work if necessary
                        if (es == null) {
                            es = vbox.getEventSource();
                            listener = es.createListener();
                            VBoxEventType events[] = { VBoxEventType.OnSessionStateChanged };
                            es.registerListener(listener, Arrays.asList(events), false);
                        }
                        do {
                            try {
                                IMachine curMachine = oSession.getMachine();
                                logger.finest("Want lock on " + oMachine.getName()
                                        + "; Waiting for release of lock on the VM \"" + curMachine.getName() + '"');
                            } catch (VBoxException e) {
                                if (e.getResultCode() == /* Not ready */0x80070005) {
                                    logger.finest("Want lock on " + oMachine.getName()
                                            + "; Waiting for release of lock");
                                }
                            }
                            ev = es.getEvent(listener, 500);

                        } while (oSession.getState() != SessionState.Unlocked);
                    }
                } catch (VBoxException e) {
                    if (e.getResultCode() != /* INVALID_OBJECT_STATE */0x80bb0007) {
                        throw new VMOperationFailedException(name, oMachine.getName(), ErrorCode.LOCK, e);
                    }
                } finally {
                    if (es != null && ev != null) {
                        es.eventProcessed(listener, ev);
                    }
                }
            } while (!haveLock);
        } finally {
            if (es != null && listener != null) {
                es.unregisterListener(listener);
            }
        }
        return true;
    }

    private boolean unlockMachine(ISession oSession, IMachine oMachine) {
        if (oSession != null && (oSession.getState() != SessionState.Unlocked)) {
            try {
                logger.finest("Unlocking the VM \"" + oMachine.getName() + '"');
                oSession.unlockMachine();
            } catch (VBoxException e) {
                // ignore race condition since things are in the right state
                if (e.getResultCode() != 0x8000FFFF /* Not locked */) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void createSharedFolder(OccpVM vm) throws OccpException {
        ISession oSession = null;
        IMachine oMachine = null;
        try {
            oMachine = ((OccpVBoxVM) vm).machine;
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            lockMachine(oSession, oMachine, LockType.Write);
            // Remove it if it already exists so we can ensure it gets the current value
            for (ISharedFolder folder : oSession.getMachine().getSharedFolders()) {
                if (folder.getName().equals("importdir")) {
                    oSession.getMachine().removeSharedFolder("importdir");
                }
            }
            oSession.getMachine().createSharedFolder("importdir", this.importDir, true, true);
            oSession.getMachine().saveSettings();
        } catch (VBoxException e) {
            if (e.getResultCode() != 0x80bb000c) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.SHARED_FOLDER, e).set(
                        "shared folder", importDir);
            }
        } finally {
            unlockMachine(oSession, oMachine);
        }
    }

    @Override
    public void waitForGuestPowerOn(OccpVM vm) throws OccpException {
        ISession oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
        IMachine oMachine = null;
        IEventSource es = null;
        IEventListener listener = null;
        try {
            oMachine = ((OccpVBoxVM) vm).machine;
            lockMachine(oSession, oMachine, LockType.Shared);
            IConsole oConsole = oSession.getConsole();
            try {
                IGuest guest = oConsole.getGuest();
                if (guest.getAdditionsStatus(AdditionsRunLevelType.Userland)) {
                    return;
                }
            } catch (VBoxException e) {
                if (e.getResultCode() != /* Not ready */0x80070005) {
                    logger.log(Level.WARNING, "Error checking guest status on the VM \"" + oMachine.getName() + '"', e);
                }
            }
            VBoxEventType events[] = { VBoxEventType.OnAdditionsStateChanged };
            es = oConsole.getEventSource();
            listener = es.createListener();
            es.registerListener(listener, Arrays.asList(events), false);
            boolean isReady = false;
            do {
                // Don't do infinite timeout, to avoid race condition from above test
                IEvent ev = es.getEvent(listener, 500);
                if (ev != null) {
                    es.eventProcessed(listener, ev);
                }
                try {
                    IGuest guest = oConsole.getGuest();
                    isReady = guest.getAdditionsStatus(AdditionsRunLevelType.Userland);
                } catch (VBoxException e) {
                    if (e.getResultCode() != /* Not ready */0x80070005) {
                        logger.log(Level.WARNING,
                                "Error checking guest status on the VM \"" + oMachine.getName() + '"', e);
                    }
                }
                logger.fine("Waiting for vbox guest: Service is ready? " + isReady);
            } while (!isReady);
            // Give the guest additions just a little more time
            Thread.sleep(1000);
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.GUEST, e);
        } finally {
            if (es != null && listener != null) {
                es.unregisterListener(listener);
            }
            if (oMachine != null && oSession.getState() == SessionState.Locked) {
                unlockMachine(oSession, oMachine);
            }
        }
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public void transferFileToVM(OccpVM vm, String sourcePath, String destPath, boolean executable)
            throws OccpException {
        ISession oSession = null;
        IMachine oMachine = null;
        IGuestSession gs = null;
        IGuestFile file = null;
        FileInputStream fis = null;
        try {
            // Open source first to make sure it exists
            Long permissions = Long.valueOf(0644);
            if (executable) {
                permissions |= 0111;
            }
            File sourceFile = new File(sourcePath);
            long fileSize = sourceFile.length();
            fis = new FileInputStream(sourcePath);

            logger.fine("Transferring " + sourcePath + " to " + destPath + " on the VM \"" + vm.getName() + '"');
            oMachine = ((OccpVBoxVM) vm).machine;
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            lockMachine(oSession, oMachine, LockType.Shared);
            IConsole oConsole = oSession.getConsole();
            IGuest guest = oConsole.getGuest();
            if (guest == null) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO,
                        "Failed to contact guest");
            }
            // TODO: This assumes it's the Vpn machine, is that ok?
            gs = guest.createSession("root", "0ccp", "", RandomStringUtils.randomAlphabetic(10));
            GuestSessionWaitResult res = gs.waitFor(Long.valueOf(GuestSessionWaitForFlag.Start.value()),
                    Long.valueOf(0));
            if (!res.equals(GuestSessionWaitResult.Start)) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO,
                        "Failed to get guest session");
            }
            try {
                // Don't overwrite OVA files unless requested to with --overwriteova
                if (gs.fileExists(destPath, false) && destPath.endsWith("ova") && !OccpAdmin.overwrite) {
                    return;
                }
                if (gs.fileExists(destPath, false)) {
                    gs.fsObjRemove(destPath);
                }
            } catch (VBoxException e) {
                if (e.getResultCode() != /* VERR_TIMEOUT */0x80bb0005) {
                    logger.log(Level.WARNING, "Unable to verify or remove old file: " + destPath, e);
                }
            }

            file = gs.fileOpen(destPath, FileAccessMode.ReadWrite, FileOpenAction.CreateOrReplace, permissions);

            byte[] buf = new byte[1024 * 64];
            Long transferred = (long) 0;
            long lastPercent = 0;
            long currentPercent = 0;
            logger.finest("Starting transfer of " + sourcePath + ", bytes: " + fileSize);
            int len = 0;
            Long wrote = (long) 0;
            while (len == wrote) {
                len = fis.read(buf, 0, buf.length);
                wrote = (long) 0;
                if (len <= 0) {
                    break;
                }
                if (len < buf.length) {
                    wrote = file.write(Arrays.copyOfRange(buf, 0, len), (long) 60000);
                } else {
                    wrote = file.write(buf, (long) 60000);
                }

                transferred += len;
                currentPercent = 100 * transferred / fileSize;
                if (currentPercent > lastPercent) {
                    logger.finest("Transfer of " + sourcePath + " is " + currentPercent + "% complete");
                    lastPercent = currentPercent;
                }
            }
        } catch (FileNotFoundException e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO, "Source file not found", e)
                    .set("source", sourcePath).set("destination", destPath);
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO, e)
                    .set("source", sourcePath).set("destination", destPath);
        } finally {
            try {
                if (fis != null) {
                    fis.close();
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing local file on: " + vm.getName(), e);
            }
            try {
                if (file != null) {
                    file.close();
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing guest file for: " + vm.getName(), e);
            }
            try {
                if (gs != null) {
                    gs.close();
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing guest session on: " + vm.getName(), e);
            }
            unlockMachine(oSession, oMachine);
        }
    }

    @Override
    public void retrieveFileFromVM(OccpVM vm, String from, String to) throws OccpException {
        ISession oSession = null;
        IMachine oMachine = null;
        IGuestSession gs = null;
        IGuestFile remoteFile = null;
        FileOutputStream fos = null;
        try {
            oMachine = ((OccpVBoxVM) vm).machine;
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            lockMachine(oSession, oMachine, LockType.Shared);
            IConsole oConsole = oSession.getConsole();
            IGuest guest = oConsole.getGuest();
            if (guest == null) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO,
                        "Failed to contact guest");
            }
            // TODO: This assumes it's the Vpn machine, is that ok?
            gs = guest.createSession("root", "0ccp", "", RandomStringUtils.randomAlphabetic(10));
            GuestSessionWaitResult res = gs.waitFor(Long.valueOf(GuestSessionWaitForFlag.Start.value()),
                    Long.valueOf(0));
            if (!res.equals(GuestSessionWaitResult.Start)) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO,
                        "Failed to get guest session");
            }
            Long permissions = Long.valueOf(0644);
            File localFile = new File(to);
            if (localFile.exists()) {
                localFile.delete();
            }
            remoteFile = gs.fileOpen("/mnt/" + from, FileAccessMode.ReadOnly, FileOpenAction.OpenExisting, permissions);
            FileStatus status = remoteFile.getStatus();
            while (status != FileStatus.Open && status != FileStatus.Error) {
                Thread.sleep(100);
                status = remoteFile.getStatus();
            }
            long lastPercent = 0;
            long currentPercent = 0;
            fos = new FileOutputStream(localFile);
            logger.finest("Querying size of " + from);
            long fileSize = gs.fileQuerySize("/mnt/" + from, true);
            logger.finest("Starting transfer of " + from + ", bytes: " + fileSize);
            Long transferred = (long) 0;
            while (transferred != fileSize) {
                final long CHUNK = 1024 * 64;
                byte[] buf = remoteFile.read(CHUNK, 0L);
                if (buf == null) {
                    remoteFile.close();
                    remoteFile = null;
                    break;
                }
                if (remoteFile.getStatus() == FileStatus.Error) {
                    break;
                }
                fos.write(buf);
                transferred += buf.length;
                currentPercent = 100 * transferred / fileSize;
                if (currentPercent > lastPercent) {
                    logger.finest("Transfer of " + from + " is " + currentPercent + "% complete");
                    lastPercent = currentPercent;
                }
            }
        } catch (FileNotFoundException e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO, "Source file not found", e)
                    .set("source", from).set("destination", to);
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_FROM, e).set("source", from)
                    .set("destination", to);
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing local file on: " + vm.getName(), e);
            }
            try {
                if (remoteFile != null) {
                    remoteFile.close();
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing guest file for: " + vm.getName(), e);
            }
            try {
                if (gs != null) {
                    gs.close();
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing guest session on: " + vm.getName(), e);
            }
            unlockMachine(oSession, oMachine);
        }
    }

    @Override
    public void runCommand(OccpVM vm, String[] cmd, boolean waitForIt) throws OccpException {
        ISession oSession = null;
        IMachine oMachine = null;
        IGuestSession gs = null;
        try {
            oMachine = ((OccpVBoxVM) vm).machine;
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            lockMachine(oSession, oMachine, LockType.Shared);
            IConsole oConsole = oSession.getConsole();
            IGuest guest = oConsole.getGuest();
            if (guest == null) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO,
                        "Failed to contact guest");
            }
            // TODO: This assumes it's the Vpn machine, is that ok?
            gs = guest.createSession("root", "0ccp", "", RandomStringUtils.randomAlphabetic(10));
            GuestSessionWaitResult res = gs.waitFor(Long.valueOf(GuestSessionWaitForFlag.Start.value()),
                    Long.valueOf(0));
            if (!res.equals(GuestSessionWaitResult.Start)) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO,
                        "Failed to get guest session");
            }
            String cmdName = cmd[0];
            String[] args = Arrays.copyOfRange(cmd, 0, cmd.length);
            logger.finest("The VM \"" + vm.getName() + "\" is Running: " + StringUtils.join(cmd, " "));
            List<ProcessCreateFlag> pcfs = new ArrayList<ProcessCreateFlag>();
            if (!waitForIt) {
                pcfs.add(ProcessCreateFlag.WaitForProcessStartOnly);
                pcfs.add(ProcessCreateFlag.IgnoreOrphanedProcesses);
            }
            IGuestProcess proc = gs.processCreate(cmdName, Arrays.asList(args), null, pcfs, Long.valueOf(0));
            if (waitForIt) {
                ProcessWaitResult result = null;
                do {
                    result = proc.waitFor((long) ProcessWaitForFlag.Terminate.value(), Long.valueOf(1000));
                    if (result == ProcessWaitResult.Terminate) {
                        logger.finest("The VM \"" + vm.getName() + "\" Running: " + StringUtils.join(cmd, " ")
                                + " returned " + proc.getExitCode());
                    }
                } while (result != ProcessWaitResult.Terminate);
                if (proc.getStatus() != ProcessStatus.TerminatedNormally) {
                    throw new VMOperationFailedException(name, vm.getName(), ErrorCode.RUN_COMMAND,
                            "Command terminated abnormally").set("command", StringUtils.join(cmd, " ")).set("Status",
                            proc.getStatus().toString());

                }
            }
        } catch (VBoxException e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.RUN_COMMAND, e).set("command",
                    StringUtils.join(cmd, " "));
        } finally {
            if (gs != null && waitForIt) {
                gs.close();
            }
            unlockMachine(oSession, oMachine);
        }
    }

    private class KeepAlive implements Runnable {
        @Override
        public void run() {
            Thread.currentThread().setName("Keepalive " + getName());
            try {
                while (!Thread.interrupted()) {
                    Thread.sleep(100000);
                    // Any simple call to keep things going
                    vbox.getRevision();
                    for (OccpVBoxVM x : cachedVMs.values()) {
                        if (x.vmMgr != mgr) {
                            logger.finest(x.vmMgr.hashCode() + ":" + x.vmMgr.getSessionObject().getState().toString());
                        }
                    }
                }
            } catch (InterruptedException e) {
                // thread shutdown requested
            }
        }
    }

    @Override
    public void exportVM(OccpVM vm, String scenario, String exportName) throws OccpException {
        IAppliance app = vbox.createAppliance();

        String ovaName = this.importDir + "/" + scenario + "/Export/" + exportName;
        /* -p doesn't seem to work */
        OccpVM vpnVM = getVM(OccpParser.SETUPVPN_NAME);
        runCommand(vpnVM, new String[] { "/bin/mkdir", "/mnt/" + scenario }, true);
        runCommand(vpnVM, new String[] { "/bin/mkdir", "/mnt/" + scenario + "/Export" }, true);
        // Must remove the old version before we import
        runCommand(vpnVM, new String[] { "/bin/rm", "-f", "/mnt/" + OccpAdmin.scenarioName + "/Export/" + exportName },
                true);
        IMachine vmref = ((OccpVBoxVM) vm).machine;
        vmref.exportTo(app, ovaName);
        List<IVirtualSystemDescription> descs = app.getVirtualSystemDescriptions();
        for (IVirtualSystemDescription desc : descs) {
            logger.finest("items: " + desc.getCount());
            Holder<List<VirtualSystemDescriptionType>> atypes = new Holder<List<VirtualSystemDescriptionType>>();
            Holder<List<String>> arefs = new Holder<List<String>>();
            Holder<List<String>> aOvfVals = new Holder<List<String>>();
            Holder<List<String>> aVBoxValues = new Holder<List<String>>();
            Holder<List<String>> aExtraConfigValues = new Holder<List<String>>();
            desc.getDescription(atypes, arefs, aOvfVals, aVBoxValues, aExtraConfigValues);
            Long items = desc.getCount();
            List<Boolean> enable = new ArrayList<Boolean>(items.intValue());
            for (int x = 0; x < items; ++x) {
                enable.add(x, true);
                logger.finest("item(" + x + "):");
                if (atypes.value.size() == 0) {
                    continue;
                }
                logger.finest("type:" + atypes.value.get(x).toString());
                logger.finest("ref:" + arefs.value.get(x));
                logger.finest("ovf:" + aOvfVals.value.get(x));
                logger.finest("recommend: " + aVBoxValues.value.get(x));
                logger.finest("extra:" + aExtraConfigValues.value.get(x));
                // Validation of this field is required when the VM came from elsewhere
                if (atypes.value.get(x).toString().equalsIgnoreCase("OS")) {
                    if (Integer.parseInt(aOvfVals.value.get(x)) <= 0) {
                        // Other
                        aOvfVals.value.set(x, "1");
                        logger.finest("changed: other");
                    }
                }
                // Force imports to always use internal
                String extra = aExtraConfigValues.value.get(x);
                if (extra.contains("type=Bridged")) {
                    extra = extra.replace("type=Bridged", "type=Internal");
                    aExtraConfigValues.value.set(x, extra);
                    logger.finest("changed: " + aExtraConfigValues.value.get(x));
                }
            }
            desc.setFinalValues(enable, aVBoxValues.value, aExtraConfigValues.value);
        }
        List<ExportOptions> options = new ArrayList<>();
        logger.fine("Exporting " + vm.getName() + " to " + ovaName);
        IProgress oProgress = app.write("ovf-1.0", options, ovaName);
        while (!oProgress.getCompleted()) {
            oProgress.waitForCompletion(5000);
            logger.info("Export of the VM \"" + vm.getName() + "\" " + oProgress.getPercent() + "% done.");
        }

        long rc = oProgress.getResultCode();
        if (rc != 0) {
            IVirtualBoxErrorInfo err = oProgress.getErrorInfo();
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.EXPORT, getVBoxErrorMessage(err)).set(
                    "virtualbox code", rc);
        }
    }

    @Override
    public void assignVMRam(OccpVM vm, int ram) throws OccpException {
        IMachine oMachine = null;
        ISession oSession = null;
        boolean locked = false;
        try {
            ISystemProperties props = vbox.getSystemProperties();
            Long maxRam = props.getMaxGuestRAM();
            IHost vboxHost = vbox.getHost();
            Long maxHostRam = vboxHost.getMemoryAvailable();
            if (ram < props.getMinGuestRAM()) {
                throw new IllegalArgumentException("Minimum ram value is " + props.getMinGuestRAM());
            } else if (ram > Math.min(maxRam, maxHostRam)) {
                throw new IllegalArgumentException("Maximum ram value is " + props.getMaxGuestRAM());
            }

            oMachine = ((OccpVBoxVM) vm).machine;
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            lockMachine(oSession, oMachine, LockType.Write);
            locked = true;
            if (oMachine.getMemorySize() != ram) {
                IMachine rwMachine = oSession.getMachine();
                rwMachine.setMemorySize((long) ram);
                rwMachine.saveSettings();
            }
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.ASSIGN_RAM, e).set("ram", ram);
        } finally {
            if (locked) {
                unlockMachine(oSession, oMachine);
            }
        }
    }

    @Override
    public void deleteVM(OccpVM vm) throws OccpException {
        OccpVBoxVM vbvm = ((OccpVBoxVM) vm);
        IMachine oMachine = vbvm.machine;
        List<IMedium> media = oMachine.unregister(CleanupMode.DetachAllReturnHardDisksOnly);
        IProgress progress = oMachine.deleteConfig(media);
        progress.waitForCompletion(-1);
        long rc = progress.getResultCode();
        if (rc != 0) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.DELETE_VM,
                    getVBoxErrorMessage(progress.getErrorInfo())).set("virtualbox code", progress.getResultCode());
        }
        cachedVMs.remove(vm.getName());
    }

    @Override
    public void deleteSnapshot(OccpVM vm, String snapshotName) throws OccpException {
        ISession oSession = null;
        IMachine oMachine = null;
        try {
            oSession = ((OccpVBoxVM) vm).vmMgr.getSessionObject();
            oMachine = ((OccpVBoxVM) vm).machine;
            lockMachine(oSession, oMachine, LockType.Write);
            ISnapshot snapshot = oMachine.findSnapshot(snapshotName);
            if (snapshot == null) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.DELETE_SNAPSHOT,
                        "Snapshot does not exist").set("snapshot", snapshotName);
            }
            IProgress oProgress = oSession.getMachine().deleteSnapshot(snapshot.getId());
            oProgress.waitForCompletion(-1);
            long rc = oProgress.getResultCode();
            if (rc != 0) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.DELETE_SNAPSHOT,
                        getVBoxErrorMessage(oProgress.getErrorInfo())).set("snapshot", snapshotName).set(
                        "virtualbox code", rc);
            }
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.DELETE_SNAPSHOT, e).set("snapshot",
                    snapshotName);
        } finally {
            unlockMachine(oSession, oMachine);
        }
    }

    @Override
    public OccpVM createVMwithISO(String vmName, String isoFilename) throws OccpException {
        IGuestOSType useGuestType = null;
        for (IGuestOSType guest : vbox.getGuestOSTypes()) {
            String id = guest.getId();
            if (id.contains("Other")) {
                useGuestType = guest;
                break;
            }
        }
        if (useGuestType == null) {
            throw new HVOperationFailedException(name, "Could not find Other Linux 32-bit OS type");
        }
        ISession oSession = this.mgr.getSessionObject();
        IMachine rwMachine = null;
        try {
            // Note: You cannot create the machine in group that doesn't exist
            IMachine newMachine = vbox.createMachine(null, vmName, null, useGuestType.getId(), null);
            IStorageController idebus = newMachine.addStorageController("IDE Controller", StorageBus.IDE);
            idebus.setControllerType(StorageControllerType.PIIX4);
            IStorageController floppybus = newMachine.addStorageController("Floppy device 0", StorageBus.Floppy);
            floppybus.setControllerType(StorageControllerType.I82078);
            INetworkAdapter net = newMachine.getNetworkAdapter((long) 0);
            net.setAdapterType(NetworkAdapterType.Virtio);
            net.setAttachmentType(NetworkAttachmentType.Bridged);
            IHost host = vbox.getHost();
            List<IHostNetworkInterface> interfaces = host.getNetworkInterfaces();
            for (IHostNetworkInterface i : interfaces) {
                if (!i.getIPAddress().isEmpty() && i.getStatus() == HostNetworkInterfaceStatus.Up) {
                    net.setBridgedInterface(i.getName());
                    break;
                }
            }
            newMachine.saveSettings();
            vbox.registerMachine(newMachine);
            lockMachine(oSession, newMachine, LockType.Write);
            rwMachine = oSession.getMachine();
            rwMachine.setGroups(Arrays.asList(new String[] { groupName }));
            boolean hasPath = (isoFilename.lastIndexOf('/') >= 0);
            String baseFileName = isoFilename;
            if (hasPath) {
                baseFileName = isoFilename.substring(isoFilename.lastIndexOf('/') + 1);
            }
            IMedium iso = vbox.openMedium(this.importDir + "/" + OccpAdmin.scenarioName + "/" + baseFileName,
                    DeviceType.DVD, AccessMode.ReadOnly, false);
            rwMachine.attachDevice("IDE Controller", 0, 0, DeviceType.DVD, iso);
            rwMachine.attachDeviceWithoutMedium("Floppy device 0", 0, 0, DeviceType.Floppy);
            rwMachine.saveSettings();
            OccpVBoxVM newvm = new OccpVBoxVM();
            newvm.machine = newMachine;
            newvm.name = vmName;
            newvm.vmMgr = this.mgr;
            return newvm;
        } catch (VBoxException e) {
            throw new VMOperationFailedException(name, vmName, ErrorCode.CREATE_VM, e);
        } finally {
            if (rwMachine != null) {
                unlockMachine(oSession, rwMachine);
            }
        }
    }

    private String getVBoxErrorMessage(IVirtualBoxErrorInfo err) {
        StringBuilder result = new StringBuilder();
        IVirtualBoxErrorInfo currentErr = err;
        while (currentErr != null) {
            if (currentErr != err) {
                result.append("\nCaused by: ");
            }
            result.append(currentErr.getText());
            currentErr = currentErr.getNext();
        }
        return result.toString();
    }

    /**
     * Returns the usage message for this hypervisor's accepted parameters
     * 
     * @return usage of this hypervisor's accepted parameters
     */
    public static String getUsage() {
        StringBuilder usage = new StringBuilder();
        usage.append("vbox:");
        usage.append("\n\t--importdir <path> [required] The full path on the host to the folder being shared with the AdminVM");
        usage.append("\n\t--password <password> [optional] The password used to authenticate with VirtualBox web services, blank passwords are specified as \"\"");
        usage.append("\n\t--url <URL> [requried] The URL to connect to VirtualBox web services");
        usage.append("\n\t--username <username> [required] The username used to authenticate with VirtualBox web services");

        return usage.toString();
    }
}
