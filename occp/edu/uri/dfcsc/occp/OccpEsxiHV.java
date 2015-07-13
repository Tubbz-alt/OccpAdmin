package edu.uri.dfcsc.occp;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.handler.MessageContext;
import javax.xml.ws.soap.SOAPFaultException;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.vmware.vim25.*;

import edu.uri.dfcsc.occp.exceptions.OccpException;
import edu.uri.dfcsc.occp.exceptions.vm.HVOperationFailedException;
import edu.uri.dfcsc.occp.exceptions.vm.VMNotFoundException;
import edu.uri.dfcsc.occp.exceptions.vm.VMOperationFailedException;
import edu.uri.dfcsc.occp.exceptions.vm.VMOperationFailedException.ErrorCode;

/**
 * VMWare ESXi specific implementation of the Occp Hypervisor interface
 * <ul>
 * <li>uses vim25.jar
 * <li>borrows liberally from VMware's sample code
 * </ul>
 * 
 * @author Kevin Bryan (bryank@cs.uri.edu)
 */
public class OccpEsxiHV implements OccpHV {
    private static Logger logger = Logger.getLogger(OccpEsxiHV.class.getName());

    private final String name;
    private boolean isLocal = false;
    private int jobs = 1;

    /**
     * If {@code cache} is {@code null}, then {@code parseArgs }must be called before {@code connect}
     * 
     * @param name The name of this hypervisor
     * @param cache Connection information, if available.
     */
    public OccpEsxiHV(String name, Map<String, String> cache) {
        this.name = name;
        if (cache != null) {
            dataCenter = cache.get("datacenter");
            datastore = cache.get("datastore");
            folder = cache.get("folder");
            publicnet = cache.get("publicnet");
            host = cache.get("host");
            url = cache.get("url");
            userName = cache.get("username");
            password = cache.get("password");
            if (cache.get("jobs") != null) {
                jobs = Integer.parseInt(cache.get("jobs"));
            }
        }
    }

    /**
     * Wrapper class for handle to an ESXi VM
     * 
     * @author Kevin Bryan (bryank@cs.uri.edu)
     */
    public static class OccpEsxiVM implements OccpHV.OccpVM {
        private ManagedObjectReference mor;
        String name;

        @Override
        public String getName() {
            return this.name;
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
            } else if (param.equalsIgnoreCase("--datacenter") && !val.startsWith("--") && !val.isEmpty()) {
                dataCenter = val;
            } else if (param.equalsIgnoreCase("--datastore") && !val.startsWith("--") && !val.isEmpty()) {
                datastore = val;
            } else if (param.equalsIgnoreCase("--publicnet") && !val.startsWith("--") && !val.isEmpty()) {
                publicnet = val;
            } else if (param.equalsIgnoreCase("--folder") && !val.startsWith("--") && !val.isEmpty()) {
                folder = val;
            } else if (param.equalsIgnoreCase("--host") && !val.startsWith("--") && !val.isEmpty()) {
                host = val;
            } else if (param.equalsIgnoreCase("--username") && !val.startsWith("--") && !val.isEmpty()) {
                userName = val;
            } else if (param.equalsIgnoreCase("--password") && !val.startsWith("--")) {
                // Allows user to specify password as "" for blank password
                password = val;
            } else if (param.equalsIgnoreCase("--hvtype") && !val.startsWith("--") && !val.isEmpty()) {
                // Assume esxi
                if (val.equals("vcenter")) {
                    this.isVirtualCenter = true;
                }
            } else if (param.equalsIgnoreCase("--jobs") && !val.startsWith("--") && !val.isEmpty()) {
                jobs = Integer.parseInt(val);
            } else {
                --ai; // Ignore this unknown parameter
            }
            val = "";
            ai += 2;
        }
        if (url == null || host == null || userName == null || publicnet == null) {
            throw new IllegalArgumentException("Expected --url, --host, --publicnet and --username arguments.");
        }
        if (this.isVirtualCenter && this.dataCenter == null) {
            throw new IllegalArgumentException("vCenter requires --datacenter argument.");
        }

        return true;
    }

    @Override
    public Map<String, String> getSaveParameters() {
        Map<String, String> params = new HashMap<String, String>();
        params.put("url", url);
        if (isVirtualCenter) {
            params.put("datacenter", dataCenter);
        }
        params.put("host", host);
        params.put("username", userName);
        if (password != null) {
            params.put("password", password);
        }
        if (this.folder != null) {
            params.put("folder", folder);
        }
        if (this.datastore != null) {
            params.put("datastore", datastore);
        }
        if (this.publicnet != null) {
            params.put("publicnet", publicnet);
        }
        if (isVirtualCenter) {
            params.put("hypervisor", "vcenter");
        } else {
            params.put("hypervisor", "esxi");
        }
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
            return this.login();
        } catch (Exception e) {
            logger.log(Level.SEVERE, this.name + "Connection error", e);
            return false;
        }
    }

    @Override
    public void disconnect() {
        try {
            if (isConnected) {
                keepAlive.interrupt();
                vimPort.logout(serviceContent.getSessionManager());
            }
            isConnected = false;
        } catch (Exception e) {
            // pass - why do we care?
        }
    }

    @Override
    public OccpVM getVM(String vmName) throws VMNotFoundException, HVOperationFailedException {
        OccpEsxiVM handle = new OccpEsxiVM();
        try {
            handle.mor = findVM(vmName, false);
            if (handle.mor == null) {
                throw new VMNotFoundException(vmName);
            }
            handle.name = vmName;
            return handle;
        } catch (InvalidPropertyFaultMsg | RuntimeFaultFaultMsg e) {
            throw new HVOperationFailedException(name, e);
        }
    }

    @Override
    public OccpVM getBaseVM(String vmName) throws VMNotFoundException, HVOperationFailedException {
        OccpEsxiVM handle = new OccpEsxiVM();
        try {
            handle.mor = findVM(vmName, true);
            if (handle.mor == null) {
                throw new VMNotFoundException(vmName);
            }
            handle.name = vmName;
            return handle;
        } catch (InvalidPropertyFaultMsg | RuntimeFaultFaultMsg e) {
            throw new HVOperationFailedException(name, e);
        }
    }

    @Override
    public void powerOnVM(OccpVM vm) throws OccpException {
        try {
            powerOnVM(((OccpEsxiVM) vm).mor);
        } catch (FileFaultFaultMsg | InsufficientResourcesFaultFaultMsg | InvalidStateFaultMsg | RuntimeFaultFaultMsg
                | TaskInProgressFaultMsg | VmConfigFaultFaultMsg | InvalidPropertyFaultMsg e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.POWER_ON, e);
        }
    }

    @Override
    public void powerOffVM(OccpVM vm) throws VMOperationFailedException {
        try {
            if (isVMOn(vm)) {
                powerOffVM(((OccpEsxiVM) vm).mor);
            }
        } catch (InvalidPropertyFaultMsg | RuntimeFaultFaultMsg | InvalidStateFaultMsg | TaskInProgressFaultMsg
                | RuntimeException e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.POWER_OFF, e);
        }
    }

    @Override
    public boolean isVMOn(OccpVM vm) {
        try {
            return isVMOn(((OccpEsxiVM) vm).mor);
        } catch (InvalidPropertyFaultMsg | RuntimeFaultFaultMsg e) {
            logger.log(Level.WARNING, "Could not determine power state of " + vm.getName() + " on " + this.name, e);
        }
        return false;
    }

    @Override
    public boolean hasSnapshot(OccpVM vm, String snapshotName) {
        try {
            return (null != getSnapshotReference(((OccpEsxiVM) vm).mor, snapshotName));
        } catch (Exception e) {
        }
        return false;
    }

    @Override
    public void createSnapshot(OccpVM vm, String snapshotName) throws OccpException {
        try {
            ManagedObjectReference mor = getSnapshotReference(((OccpEsxiVM) vm).mor, snapshotName);
            // already exists
            if (mor != null) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.CREATE_SNAPSHOT,
                        "Snapshot already exists").set("snapshot", snapshotName);
            }
            createSnapshot(((OccpEsxiVM) vm).mor, snapshotName);
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.CREATE_SNAPSHOT, e).set("snapshot",
                    snapshotName);
        }
    }

    @Override
    public void cloneVM(OccpVM vm, String cloneName, String snapshotBase) throws OccpException {
        try {
            if (this.isVirtualCenter) {
                linkedCloneVM(((OccpEsxiVM) vm).mor, cloneName, snapshotBase);
            } else {
                fileCloneVM(((OccpEsxiVM) vm).mor, cloneName);
            }
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.CLONE, e).set("original", vm.getName())
                    .set("clone", cloneName).set("snapshot", snapshotBase);
        }
    }

    @Override
    public String getVMMac(OccpVM vm, int iFaceNumber) {
        ManagedObjectReference mor = ((OccpEsxiVM) vm).mor;

        ArrayList<VirtualEthernetCard> listOfCards;
        try {
            listOfCards = getVMNics(mor);
            if (listOfCards.isEmpty()) {
                return null;
            }
            return listOfCards.get(iFaceNumber).getMacAddress();
        } catch (InvalidPropertyFaultMsg | RuntimeFaultFaultMsg e) {
            logger.log(Level.FINEST, "Error getting MAC address of " + vm.getName() + " Card " + iFaceNumber, e);
        }
        return null;
    }

    @Override
    public boolean networkExists(String netName) {
        try {
            return _networkExists(netName, true);
        } catch (Exception e) {
            logger.log(Level.FINEST, "Failed looking for network: " + netName, e);
        }
        return false;
    }

    @Override
    public void createNetwork(String netName) throws OccpException {
        try {
            addVirtualSwitchPortGroup(netName, netName);
        } catch (Exception e) {
            throw new HVOperationFailedException(name, "Failed adding network", e).set("network", netName);
        }
    }

    @Override
    public boolean isVMOnNetwork(OccpVM vm, String netName) {
        ManagedObjectReference mor = ((OccpEsxiVM) vm).mor;
        try {
            ArrayOfManagedObjectReference aomor = (ArrayOfManagedObjectReference) getEntityProp(mor, "network");
            List<ManagedObjectReference> networks = aomor.getManagedObjectReference();

            for (ManagedObjectReference net : networks) {
                String testName = (String) getEntityProp(net, "name");
                if (testName.equals(netName)) {
                    return true;
                }
            }
        } catch (Exception e) {
            logger.log(Level.FINEST, "Error checking " + vm.getName() + " is on " + netName, e);
        }
        return false;
    }

    @Override
    public void assignVMNetworks(OccpVM vm, List<String> networks) throws OccpException {
        ManagedObjectReference vmMor = ((OccpEsxiVM) vm).mor;
        VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();

        ArrayList<VirtualEthernetCard> cards;
        try {
            cards = getVMNics(vmMor);
        } catch (InvalidPropertyFaultMsg | RuntimeFaultFaultMsg e1) {
            throw new VMOperationFailedException(this.name, vm.getName(), ErrorCode.ASSIGN_NETWORK, e1);
        }
        ArrayList<VirtualDeviceConfigSpec> devices = new ArrayList<VirtualDeviceConfigSpec>();
        int cardIndex = 0;
        boolean change = false;
        for (String netName : networks) {
            if (netName == null) {
                // Do not reconfigure this interface
                ++cardIndex;
                continue;
            }
            VirtualDeviceConfigSpec nic = new VirtualDeviceConfigSpec();
            nic.setOperation(VirtualDeviceConfigSpecOperation.EDIT);
            VirtualEthernetCard card;
            VirtualDeviceConnectInfo connect;
            VirtualEthernetCardNetworkBackingInfo nicBacking;
            if (cardIndex >= cards.size()) {
                // Need to add another card
                nic.setOperation(VirtualDeviceConfigSpecOperation.ADD);
                card = new VirtualVmxnet3();
                card.setMacAddress(null);
                card.setAddressType("Generated");
                connect = new VirtualDeviceConnectInfo();
                card.setConnectable(connect);
                nicBacking = new VirtualEthernetCardNetworkBackingInfo();
                nicBacking.setDeviceName(netName);
                card.setBacking(nicBacking);
                change = true;
            } else {
                card = cards.get(cardIndex);
            }

            // Ensure all cards are the best type (requires guest support)
            if (!(card instanceof VirtualVmxnet3)) {
                nic.setOperation(VirtualDeviceConfigSpecOperation.ADD);
                VirtualEthernetCard oldcard = card;
                card = new VirtualVmxnet3();
                card.setMacAddress(null);
                card.setAddressType(oldcard.getAddressType());
                /*
                 * card.setSlotInfo(oldcard.getSlotInfo());
                 * card.setUnitNumber(oldcard.getUnitNumber());
                 * card.setControllerKey(oldcard.getControllerKey());
                 */
                connect = new VirtualDeviceConnectInfo();
                card.setConnectable(connect);
                nicBacking = new VirtualEthernetCardNetworkBackingInfo();
                nicBacking.setDeviceName(netName);
                card.setBacking(nicBacking);
                VirtualDeviceConfigSpec oldnic = new VirtualDeviceConfigSpec();
                oldnic.setOperation(VirtualDeviceConfigSpecOperation.REMOVE);
                oldnic.setDevice(oldcard);
                devices.add(oldnic);
                change = true;
            }

            // Ensure that the network cards are and will be connected
            connect = card.getConnectable();
            if (!connect.isStartConnected()) {
                connect.setStartConnected(true);
                change = true;
            }

            nicBacking = (VirtualEthernetCardNetworkBackingInfo) card.getBacking();
            if (!nicBacking.getDeviceName().equals(netName)) {
                nicBacking.setDeviceName(netName);
                card.setBacking(nicBacking);
                change = true;
            }

            // Only add it to the list if we need to change it
            if (change) {
                nic.setDevice(card);
                devices.add(nic);
            }

            ++cardIndex;
        }
        // Remove extra cards
        for (int cardNum = cards.size(); cardNum > cardIndex; --cardNum) {
            VirtualDeviceConfigSpec nic = new VirtualDeviceConfigSpec();
            VirtualEthernetCard card = cards.get(cardNum - 1);
            nic.setDevice(card);
            nic.setOperation(VirtualDeviceConfigSpecOperation.REMOVE);
            devices.add(nic);
            change = true;
        }
        try {
            if (change) {
                spec.getDeviceChange().addAll(devices);
                ManagedObjectReference task = vimPort.reconfigVMTask(vmMor, spec);
                getTaskResultAfterDone(task);
            }
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.ASSIGN_NETWORK, e);
        }
    }

    @Override
    public void revertToSnapshot(OccpVM vm, String snapshotName) throws OccpException {
        try {
            revertSnapshot(((OccpEsxiVM) vm).mor, snapshotName);
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.REVERT_SNAPSHOT, e).set("snapshot",
                    snapshotName);
        }
    }

    @Override
    public void attachFloppy(OccpVM vm, String filename) throws OccpException {
        try {
            if (!uploadFloppy(((OccpEsxiVM) vm).mor, filename, true)) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.ATTACH_FLOPPY).set("filename",
                        filename);
            }
            attachFloppy(((OccpEsxiVM) vm).mor, filename);
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.ATTACH_FLOPPY, e).set("filename",
                    filename);
        }
    }

    private boolean assignVMGroup(String vmName, ManagedObjectReference vmmor) throws OccpException {
        try {
            VirtualMachineConfigSpec configSpec = new VirtualMachineConfigSpec();
            OptionValue ov = new OptionValue();
            ov.setKey("occp.group");
            ov.setValue(groupName);
            configSpec.getExtraConfig().add(ov);
            ManagedObjectReference task = vimPort.reconfigVMTask(vmmor, configSpec);
            getTaskResultAfterDone(task);
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vmName, ErrorCode.ASSIGN_GROUP, e).set("group", groupName);
        }
        return true;
    }

    @Override
    public void setBootCD(OccpVM vm) throws VMOperationFailedException {
        try {
            ManagedObjectReference vmmor = ((OccpEsxiVM) vm).mor;
            VirtualMachineConfigInfo info = (VirtualMachineConfigInfo) getEntityProp(vmmor, "config");
            VirtualMachineBootOptions bo = info.getBootOptions();
            List<VirtualMachineBootOptionsBootableDevice> oldorder = bo.getBootOrder();
            if (!oldorder.isEmpty()) {
                VirtualMachineBootOptionsBootableDevice dev = oldorder.get(0);
                // Don't reconfigure if we don't need to
                if (dev instanceof VirtualMachineBootOptionsBootableCdromDevice) {
                    return;
                }
            }
            // Make the CD be first
            oldorder.add(0, new VirtualMachineBootOptionsBootableCdromDevice());
            VirtualMachineConfigSpec configSpec = new VirtualMachineConfigSpec();
            configSpec.setBootOptions(bo);
            ManagedObjectReference task = vimPort.reconfigVMTask(vmmor, configSpec);
            getTaskResultAfterDone(task);
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.BOOT_ORDER, e);
        }
    }

    @Override
    public void importVM(String vmName, String fileName) throws OccpException {
        try {
            importVApp(vmName, fileName);
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vmName, ErrorCode.IMPORT, e).set("filename", fileName);
        }
    }

    @Override
    public void createSharedFolder(OccpVM vm) {
        /* This does not make sense for this hypervisor, since we can upload things directly */
        return;
    }

    @Override
    public void waitForGuestPowerOn(OccpVM vm) throws VMOperationFailedException {
        String[] opts = new String[] { "guest.guestOperationsReady" };
        String[] opt = new String[] { "guest.guestOperationsReady" };
        try {
            waitForValues(((OccpEsxiVM) vm).mor, opts, opt, new Object[][] { new Object[] { true } });
        } catch (InvalidPropertyFaultMsg | RuntimeFaultFaultMsg e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.GUEST, e);
        }
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public void transferFileToVM(OccpVM vm, String sourcePath, String destPath, boolean executable)
            throws OccpException {
        /* Assumes guest operations are ready */
        ManagedObjectReference vmmor = ((OccpEsxiVM) vm).mor;
        ManagedObjectReference guestOpManager = serviceContent.getGuestOperationsManager();
        try {
            ManagedObjectReference fileManagerRef = (ManagedObjectReference) getEntityProp(guestOpManager,
                    "fileManager");
            NamePasswordAuthentication auth = new NamePasswordAuthentication();
            auth.setUsername("root");
            auth.setPassword("0ccp");
            auth.setInteractiveSession(false);
            GuestPosixFileAttributes guestFileAttributes = new GuestPosixFileAttributes();
            Long permissions = (long) 0644;
            if (executable) {
                permissions |= 0111;
            }
            guestFileAttributes.setPermissions(permissions);
            File localFile = new File(sourcePath);
            long fileSize = localFile.length();
            String fileUploadUrl = null;
            int tries = 0;
            do {
                try {
                    fileUploadUrl = vimPort.initiateFileTransferToGuest(fileManagerRef, vmmor, auth, destPath,
                            guestFileAttributes, fileSize, true);
                } catch (InvalidStateFaultMsg isfme) {
                    logger.finer("Invalid state transferring file, retrying " + tries);
                    Thread.sleep(500);
                }
                ++tries;
            } while (tries < 5 && fileUploadUrl == null);
            if (fileUploadUrl == null) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO,
                        "initiateFileTransferToGuest failed").set("source", sourcePath).set("destination", destPath);
            }
            URL tempUrlObject = new URL(this.url);
            /* Strange VMware method to get the correct hostname into the URL */
            fileUploadUrl = fileUploadUrl.replaceAll("\\*", tempUrlObject.getHost());
            HttpURLConnection conn = null;
            URL uploadUrl = new URL(fileUploadUrl);
            conn = (HttpURLConnection) uploadUrl.openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Length", Long.toString(fileSize));
            OutputStream out = conn.getOutputStream();
            // Likely only a good idea for small files
            byte[] data = FileUtils.readFileToByteArray(new File(sourcePath));

            out.write(data, 0, (int) fileSize);
            out.close();
            int returnErrorCode = conn.getResponseCode();
            conn.disconnect();
            if (HttpsURLConnection.HTTP_OK != returnErrorCode) {
                throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO, "HTTP PUT failed").set(
                        "httperror", returnErrorCode).set("url", uploadUrl);
            }
        } catch (InvalidPropertyFaultMsg | RuntimeFaultFaultMsg | FileFaultFaultMsg | GuestOperationsFaultFaultMsg
                | TaskInProgressFaultMsg | IOException | InterruptedException e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.TRANSFER_TO, e)
                    .set("source", sourcePath).set("destination", destPath);
        }
    }

    @Override
    public void retrieveFileFromVM(OccpVM vm, String from, String to) {
        // Placeholder, unused
        return;
    }

    @Override
    public void runCommand(OccpVM vm, String[] cmd, boolean waitForIt) throws OccpException {
        /* Assumes guest operations are ready */
        ManagedObjectReference vmmor = ((OccpEsxiVM) vm).mor;
        ManagedObjectReference guestOpManager = serviceContent.getGuestOperationsManager();
        String cmdName = cmd[0];
        String[] args = Arrays.copyOfRange(cmd, 1, cmd.length);
        String arguments = StringUtils.join(args, ' ');
        try {
            ManagedObjectReference progManagerRef = (ManagedObjectReference) getEntityProp(guestOpManager,
                    "processManager");
            NamePasswordAuthentication auth = new NamePasswordAuthentication();
            auth.setUsername("root");
            auth.setPassword("0ccp");
            auth.setInteractiveSession(false);
            GuestProgramSpec spec = new GuestProgramSpec();
            spec.setProgramPath(cmdName);
            spec.setArguments(arguments);
            vimPort.startProgramInGuest(progManagerRef, vmmor, auth, spec);
        } catch (InvalidPropertyFaultMsg | RuntimeFaultFaultMsg | FileFaultFaultMsg | GuestOperationsFaultFaultMsg
                | InvalidStateFaultMsg | TaskInProgressFaultMsg e) {
            logger.log(Level.FINEST, "Unexpected error running command", e);
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.RUN_COMMAND, e).set("command", cmdName)
                    .set("arguments", arguments);
        }
    }

    // Need VM to determine folder
    private boolean uploadFloppy(ManagedObjectReference vm, String fileName, boolean scenarioDir)
            throws IOException, InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        HttpsURLConnection conn = null;
        BufferedOutputStream bos = null;

        int bytesRead, bytesAvailable, bufferSize;
        byte[] buffer;
        int maxBufferSize = 64 * 1024;
        // File names are given relative to the scenario directory
        File filefile = null;
        if (scenarioDir) {
            filefile = new File(OccpAdmin.scenarioBaseDir.resolve(fileName).toString());
        } else {
            filefile = new File(fileName);
        }
        boolean hasPath = (fileName.lastIndexOf('/') >= 0);
        String baseFileName = fileName;
        if (hasPath) {
            baseFileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        }
        long diskCapacity = filefile.length();

        try {
            HostnameVerifier hv = new HostnameVerifier() {
                @Override
                public boolean verify(String urlHostName, SSLSession session) {
                    if (!urlHostName.equals(session.getPeerHost())) {
                        logger.warning("URL Host: " + urlHostName + " vs. " + session.getPeerHost());
                    }
                    return true;
                }
            };
            HttpsURLConnection.setDefaultHostnameVerifier(hv);

            // Construct the path for the upload
            String serviceUrl = this.url;
            serviceUrl = serviceUrl.substring(0, serviceUrl.lastIndexOf("sdk") - 1);
            String vmPathFileName = getDatastorePath(vm);
            // Format: [datastore] vmname/vmname.vmx
            if (vmPathFileName == null) {
                throw new FileNotFoundException("Could not find datastore path");
            }
            // Use the same datastore, for now
            String datastore = vmPathFileName.substring(1, vmPathFileName.indexOf("]"));
            // Extract the Folder name without the datastore
            String vmPathFolderName = vmPathFileName.substring(vmPathFileName.indexOf(" ") + 1,
                    vmPathFileName.lastIndexOf("/"));

            String uri = "";
            if (this.isVirtualCenter && this.dataCenter != null) {
                uri = serviceUrl + "/folder/" + vmPathFolderName + "/" + baseFileName + "?dcPath=" + this.dataCenter
                        + "&dsName=" + datastore;
            } else {
                uri = serviceUrl + "/folder/" + vmPathFolderName + "/" + baseFileName + "?dsName=" + datastore;
            }
            logger.finest("Uploading to: " + uri);
            // Start the connection
            URL uploadUrl = new URL(uri);
            conn = (HttpsURLConnection) uploadUrl.openConnection();

            // Maintain session (required that we use cookie information from login)
            @SuppressWarnings("unchecked")
            List<String> cookies = (List<String>) headers.get("Set-cookie");
            String cookieValue = "";

            cookieValue = cookies.get(0);
            StringTokenizer tokenizer = new StringTokenizer(cookieValue, ";");
            cookieValue = tokenizer.nextToken();
            String path = "$" + tokenizer.nextToken();
            String cookie = "$Version=\"1\"; " + cookieValue + "; " + path;

            // set the cookie in the new request header
            Map<String, List<String>> map = new HashMap<String, List<String>>();
            map.put("Cookie", Collections.singletonList(cookie));
            ((BindingProvider) vimPort).getRequestContext().put(MessageContext.HTTP_REQUEST_HEADERS, map);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setChunkedStreamingMode(maxBufferSize);
            boolean put = true; // Always overwrite (hope this works when it isn't there)
            if (put) {
                conn.setRequestMethod("PUT");
                conn.setRequestProperty("Overwrite", "t");
            } else {
                conn.setRequestMethod("POST");
            }
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.setRequestProperty("Content-Type", "application/x-vnd.vmware-streamVmdk");
            conn.setRequestProperty("Content-Length", String.valueOf(diskCapacity));
            conn.setRequestProperty("Expect", "100-continue");

            bos = new BufferedOutputStream(conn.getOutputStream());
            try (InputStream io = new FileInputStream(filefile.getAbsolutePath());
                    BufferedInputStream bis = new BufferedInputStream(io)) {
                bytesAvailable = bis.available();
                bufferSize = Math.min(bytesAvailable, maxBufferSize);
                buffer = new byte[bufferSize];
                bytesRead = bis.read(buffer, 0, bufferSize);
                long bytesWrote = bytesRead;
                while (bytesRead >= 0) {
                    bos.write(buffer, 0, bufferSize);
                    bos.flush();
                    bytesAvailable = bis.available();
                    bufferSize = Math.min(bytesAvailable, maxBufferSize);
                    bytesWrote += bufferSize;
                    buffer = null;
                    buffer = new byte[bufferSize];
                    bytesRead = bis.read(buffer, 0, bufferSize);
                    if ((bytesRead == 0) && (bytesWrote >= diskCapacity)) {
                        bytesRead = -1;
                    }
                }
                DataInputStream dis = new DataInputStream(conn.getInputStream());
                dis.close();
                bis.close();
            }
        } finally {
            try {
                if (bos != null) {
                    bos.flush();
                    bos.close();
                }
                if (conn != null) {
                    conn.disconnect();
                }
            } catch (SOAPFaultException sfe) {
                printSoapFaultException(sfe);
                throw sfe;
            } catch (Exception e) {
                throw e;
            }
        }
        return true;
    }

    private void attachFloppy(ManagedObjectReference vm, String filename)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg, FileNotFoundException, ConcurrentAccessFaultMsg,
            DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        VirtualMachineConfigSpec configSpec = new VirtualMachineConfigSpec();
        List<VirtualDeviceConfigSpec> deviceConfigSpecArr = new ArrayList<VirtualDeviceConfigSpec>();

        VirtualDeviceConfigSpec deviceConfigSpec = new VirtualDeviceConfigSpec();

        List<VirtualDevice> deviceArr = ((ArrayOfVirtualDevice) getEntityProp(vm, "config.hardware.device"))
                .getVirtualDevice();
        VirtualDevice floppy = null;

        for (VirtualDevice device : deviceArr) {
            if (device instanceof VirtualFloppy) {
                Description info = device.getDeviceInfo();
                if (info != null) {
                    if (info.getLabel().equalsIgnoreCase("Floppy drive 1")) {
                        floppy = device;
                        break;
                    }
                }
            }
        }
        if (floppy == null) {
            throw new FileNotFoundException("Could not find the floppy drive");
        }

        VirtualDeviceConnectInfo cInfo = new VirtualDeviceConnectInfo();
        cInfo.setConnected(true);
        cInfo.setStartConnected(true);

        floppy.setConnectable(cInfo);

        VirtualFloppyImageBackingInfo backingInfo = new VirtualFloppyImageBackingInfo();
        String vmPathFileName = getDatastorePath(vm);
        if (vmPathFileName == null) {
            throw new FileNotFoundException("Could not find datastore path");
        }
        String vmPathFolderName = vmPathFileName.substring(0, vmPathFileName.lastIndexOf("/"));
        boolean hasPath = (filename.lastIndexOf('/') >= 0);
        String baseFileName = filename;
        if (hasPath) {
            baseFileName = filename.substring(filename.lastIndexOf('/') + 1);
        }
        backingInfo.setFileName(vmPathFolderName + "/" + baseFileName);
        floppy.setBacking(backingInfo);
        deviceConfigSpec.setDevice(floppy);
        deviceConfigSpec.setOperation(VirtualDeviceConfigSpecOperation.EDIT);

        deviceConfigSpecArr.add(deviceConfigSpec);
        configSpec.getDeviceChange().addAll(deviceConfigSpecArr);

        ManagedObjectReference task = vimPort.reconfigVMTask(vm, configSpec);
        getTaskResultAfterDone(task);
    }

    private boolean attachISO(ManagedObjectReference vm, String filename)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg, FileNotFoundException, ConcurrentAccessFaultMsg,
            DuplicateNameFaultMsg, FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidDatastoreFaultMsg,
            InvalidNameFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        VirtualMachineConfigSpec configSpec = new VirtualMachineConfigSpec();
        List<VirtualDeviceConfigSpec> deviceConfigSpecArr = new ArrayList<VirtualDeviceConfigSpec>();

        VirtualDeviceConfigSpec deviceConfigSpec = new VirtualDeviceConfigSpec();

        List<VirtualDevice> deviceArr = ((ArrayOfVirtualDevice) getEntityProp(vm, "config.hardware.device"))
                .getVirtualDevice();
        VirtualDevice cdrom = null;

        for (VirtualDevice device : deviceArr) {
            if (device instanceof VirtualCdrom) {
                // Assume one, use first
                cdrom = device;
            }
        }
        if (cdrom == null) {
            throw new FileNotFoundException("Could not find the cdrom drive");
        }

        VirtualDeviceConnectInfo cInfo = new VirtualDeviceConnectInfo();
        cInfo.setConnected(true);
        cInfo.setStartConnected(true);

        cdrom.setConnectable(cInfo);

        VirtualCdromIsoBackingInfo backingInfo = new VirtualCdromIsoBackingInfo();
        String vmPathFileName = getDatastorePath(vm);
        if (vmPathFileName == null) {
            throw new FileNotFoundException("Could not find datastore path");
        }
        String vmPathFolderName = vmPathFileName.substring(0, vmPathFileName.lastIndexOf("/"));
        boolean hasPath = (filename.lastIndexOf('/') >= 0);
        String baseFileName = filename;
        if (hasPath) {
            baseFileName = filename.substring(filename.lastIndexOf('/') + 1);
        }
        backingInfo.setFileName(vmPathFolderName + "/" + baseFileName);
        cdrom.setBacking(backingInfo);
        deviceConfigSpec.setDevice(cdrom);
        deviceConfigSpec.setOperation(VirtualDeviceConfigSpecOperation.EDIT);

        deviceConfigSpecArr.add(deviceConfigSpec);
        configSpec.getDeviceChange().addAll(deviceConfigSpecArr);

        ManagedObjectReference task = vimPort.reconfigVMTask(vm, configSpec);
        getTaskResultAfterDone(task);
        return true;
    }

    private final ManagedObjectReference SVC_INST_REF = new ManagedObjectReference();
    private ManagedObjectReference propCollectorRef;
    private ManagedObjectReference rootRef;
    private VimService vimService;
    private VimPortType vimPort;
    private ServiceContent serviceContent;
    private final String SVC_INST_NAME = "ServiceInstance";

    /**
     * only used if URL points to a vCenter instance. We put all OCCP on one host
     */
    private String dataCenter;
    private String host;
    private String url;
    private String userName;
    private String password = null;
    private String datastore = null;
    private String folder = null;
    private String publicnet = null;

    private final String groupName = "occp-" + OccpAdmin.scenarioName;
    private boolean isConnected = false;
    private boolean isVirtualCenter = false;
    // Needed to collect info at login for later
    private Map<String, Object> headers = new HashMap<String, Object>();
    private ManagedObjectReference hostmor;
    private ManagedObjectReference dsMor;
    private ManagedObjectReference folderRef;
    private Thread keepAlive;

    private boolean isOccp(ManagedObjectReference mor) throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        VirtualMachineConfigInfo configInfo = (VirtualMachineConfigInfo) getEntityProp(mor, "config");
        if (configInfo == null) {
            return false;
        }
        List<OptionValue> properties = configInfo.getExtraConfig();
        for (OptionValue prop : properties) {
            if (prop.getKey().equals("occp.group")) {
                if (prop.getValue().equals(groupName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Return a list of all of the network adapters
     * 
     * @param vm - A reference to the VM
     * @return The list of virtual network adapters
     * @throws RuntimeFaultFaultMsg
     * @throws InvalidPropertyFaultMsg
     */
    private ArrayList<VirtualEthernetCard> getVMNics(ManagedObjectReference vm)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        ArrayList<VirtualEthernetCard> listOfCards = new ArrayList<VirtualEthernetCard>();
        VirtualMachineConfigInfo info = (VirtualMachineConfigInfo) getEntityProp(vm, "config");
        VirtualHardware hw = info.getHardware();
        for (VirtualDevice d : hw.getDevice()) {
            if (d instanceof VirtualEthernetCard) {
                listOfCards.add((VirtualEthernetCard) d);
            }
        }
        return listOfCards;
    }

    /**
     * Establishes session with the virtual center server.
     * 
     * @return Success/Failure
     * @throws RuntimeFaultFaultMsg
     * @throws InvalidLocaleFaultMsg
     * @throws InvalidLoginFaultMsg
     * @throws InvalidPropertyFaultMsg
     * @throws HVOperationFailedException
     */
    @SuppressWarnings("unchecked")
    private boolean login()
            throws RuntimeFaultFaultMsg, InvalidLocaleFaultMsg, InvalidLoginFaultMsg, InvalidPropertyFaultMsg,
            HVOperationFailedException {
        HostnameVerifier hv = new HostnameVerifier() {
            @Override
            public boolean verify(String urlHostName, SSLSession session) {
                if (!urlHostName.equals(session.getPeerHost())) {
                    logger.warning("Warning: URL Host: " + urlHostName + " vs. " + session.getPeerHost());
                }
                return true;
            }
        };

        HttpsURLConnection.setDefaultHostnameVerifier(hv);

        SVC_INST_REF.setType(SVC_INST_NAME);
        SVC_INST_REF.setValue(SVC_INST_NAME);

        if (!(this.url.endsWith("/sdk") || this.url.endsWith("/sdk/"))) {
            logger.warning("VMware services URL's usually end with /sdk/");
        }

        vimService = new VimService();
        vimPort = vimService.getVimPort();
        Map<String, Object> ctxt = ((BindingProvider) vimPort).getRequestContext();

        ctxt.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, url);
        ctxt.put(BindingProvider.SESSION_MAINTAIN_PROPERTY, true);

        serviceContent = vimPort.retrieveServiceContent(SVC_INST_REF);
        headers = (Map<String, Object>) ((BindingProvider) vimPort).getResponseContext().get(
                MessageContext.HTTP_RESPONSE_HEADERS);
        if (password == null) {
            password = new String(OccpAdmin.getPassword(this.userName + "@" + this.url));
        }
        vimPort.login(serviceContent.getSessionManager(), userName, password, null);
        isConnected = true;

        propCollectorRef = serviceContent.getPropertyCollector();
        rootRef = serviceContent.getRootFolder();
        AboutInfo ai = serviceContent.getAbout();
        logger.finest("API: " + ai.getApiType());
        logger.finest("osType: " + ai.getOsType());
        logger.finest("productLineId: " + ai.getProductLineId());
        logger.finest("name: " + ai.getName());
        // Both workstation and ESXi return "HostAgent" for ApiType
        // ProductLineId: esxi: embeddedEsx
        if (ai.getProductLineId().equals("vpx")) {
            this.isVirtualCenter = true;
        } else if (ai.getProductLineId().equals("ws")) {
            throw new HVOperationFailedException(this.name, "VMware Workstation is not supported");
        }

        if (this.folder != null) {
            if (folder.endsWith("/")) {
                folder = folder.substring(0, folder.length());
            }
            if (OccpAdmin.scenarioName != null && !folder.endsWith(OccpAdmin.scenarioName)) {
                folder = folder.concat("/" + groupName);
            }
            String[] folders = folder.split("/");
            int levels = folders.length;
            folderRef = getMOREFsInFolder(rootRef, "Folder", "vm", null);
            try {
                for (String parent : folders) {
                    ManagedObjectReference parentFolderRef = folderRef;
                    folderRef = getMOREFsInFolder(folderRef, "Folder", parent, null);
                    --levels;
                    // Create only the last level
                    if (levels == 0 && folderRef == null) {
                        try {
                            folderRef = vimPort.createFolder(parentFolderRef, parent);
                        } catch (DuplicateNameFaultMsg e) {
                            // Race condition; we just checked
                        } catch (InvalidNameFaultMsg e) {
                            logger.severe("Invalid folder name: " + parent);
                            return false;
                        }
                    }
                }
            } catch (SOAPFaultException e) {
                logger.severe("Check your hypervisor configuration's folder path on \"" + this.getName() + '"');
                return false;
            }
        } else {
            ManagedObjectReference dcMor;
            if (this.isVirtualCenter) {
                dcMor = vimPort.findChild(serviceContent.getSearchIndex(), rootRef, dataCenter);
            } else {
                dcMor = vimPort.findChild(serviceContent.getSearchIndex(), rootRef, "ha-datacenter");
            }
            folderRef = (ManagedObjectReference) getEntityProp(dcMor, "vmFolder");
        }
        hostmor = getMOREFsInFolder(rootRef, "HostSystem", host, null);
        if (hostmor == null) {
            logger.severe("Host \"" + host + "\" not found on hypervisor \"" + name + "\"");
            return false;
        }
        List<ManagedObjectReference> dsList = ((ArrayOfManagedObjectReference) getEntityProp(hostmor, "datastore"))
                .getManagedObjectReference();
        if (dsList.isEmpty()) {
            throw new RuntimeException("No Datastores accessible from host " + host);
        }
        if (datastore == null) {
            dsMor = dsList.get(0);
        } else {
            for (ManagedObjectReference ds : dsList) {
                if (datastore.equalsIgnoreCase((String) getEntityProp(ds, "name"))) {
                    dsMor = ds;
                    break;
                }
            }
        }
        if (dsMor == null) {
            if (datastore != null) {
                throw new RuntimeException("No Datastore by name " + datastore + " is accessible from host " + host);
            }
            throw new RuntimeException("No Datastores accessible from host " + host);
        }
        keepAlive = new Thread(new KeepAlive());
        keepAlive.start();
        return true;
    }

    private boolean powerOnVM(ManagedObjectReference vm)
            throws FileFaultFaultMsg, InsufficientResourcesFaultFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg,
            TaskInProgressFaultMsg, VmConfigFaultFaultMsg, InvalidPropertyFaultMsg {
        ManagedObjectReference taskmor = vimPort.powerOnVMTask(vm, hostmor);
        getTaskResultAfterDone(taskmor);
        return true;
    }

    private void powerOffVM(ManagedObjectReference vm)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg {
        ManagedObjectReference taskmor = vimPort.powerOffVMTask(vm);
        getTaskResultAfterDone(taskmor);
    }

    private boolean isVMOn(ManagedObjectReference vm) throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        VirtualMachineRuntimeInfo info = (VirtualMachineRuntimeInfo) getEntityProp(vm, "runtime");
        VirtualMachinePowerState state = info.getPowerState();
        if (state != VirtualMachinePowerState.POWERED_OFF) {
            return true;
        }
        return false;
    }

    /**
     * Uses the new RetrievePropertiesEx method to emulate the now deprecated RetrieveProperties method
     * 
     * @param listpfs
     * @return list of object content
     */
    private List<ObjectContent> retrievePropertiesAllObjects(List<PropertyFilterSpec> listpfs) {

        RetrieveOptions propObjectRetrieveOpts = new RetrieveOptions();

        List<ObjectContent> listobjcontent = new ArrayList<ObjectContent>();

        try {
            RetrieveResult rslts = vimPort.retrievePropertiesEx(propCollectorRef, listpfs, propObjectRetrieveOpts);
            if (rslts != null && rslts.getObjects() != null && !rslts.getObjects().isEmpty()) {
                listobjcontent.addAll(rslts.getObjects());
            }
            String token = null;
            if (rslts != null && rslts.getToken() != null) {
                token = rslts.getToken();
            }
            while (token != null && !token.isEmpty()) {
                rslts = vimPort.continueRetrievePropertiesEx(propCollectorRef, token);
                token = null;
                if (rslts != null) {
                    token = rslts.getToken();
                    if (rslts.getObjects() != null && !rslts.getObjects().isEmpty()) {
                        listobjcontent.addAll(rslts.getObjects());
                    }
                }
            }
        } catch (SOAPFaultException sfe) {
            printSoapFaultException(sfe);
        } catch (InvalidPropertyFaultMsg e) {
            logger.log(Level.SEVERE, "Logic error, or invalid object configuration", e);
        } catch (RuntimeFaultFaultMsg e) {
            logger.log(Level.SEVERE, "Runtime failure", e);
        }

        return listobjcontent;
    }

    private void updateValues(String[] props, Object[] vals, PropertyChange propchg) {
        for (int findi = 0; findi < props.length; findi++) {
            if (propchg.getName().lastIndexOf(props[findi]) >= 0) {
                if (propchg.getOp() == PropertyChangeOp.REMOVE) {
                    vals[findi] = "";
                } else {
                    vals[findi] = propchg.getVal();
                }
            }
        }
    }

    /**
     * Handle Updates for a single object. waits till expected values of properties to check are reached Destroys the
     * ObjectFilter when done.
     * 
     * @param objmor MOR of the Object to wait for
     * @param filterProps Properties list to filter
     * @param endWaitProps Properties list to check for expected values these be properties of a property in the filter
     *            properties list
     * @param expectedVals values for properties to end the wait
     * @return true indicating expected values were met, and false otherwise
     * @throws RuntimeFaultFaultMsg
     * @throws InvalidPropertyFaultMsg
     */
    private synchronized Object[] waitForValues(ManagedObjectReference objmor, String[] filterProps,
            String[] endWaitProps, Object[][] expectedVals) throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        // version string is initially null
        String version = "";
        Object[] endVals = new Object[endWaitProps.length];
        Object[] filterVals = new Object[filterProps.length];

        PropertyFilterSpec spec = new PropertyFilterSpec();
        ObjectSpec oSpec = new ObjectSpec();
        oSpec.setObj(objmor);
        oSpec.setSkip(false);
        spec.getObjectSet().add(oSpec);

        PropertySpec pSpec = new PropertySpec();
        pSpec.getPathSet().addAll(Arrays.asList(filterProps));
        pSpec.setType(objmor.getType());
        spec.getPropSet().add(pSpec);

        ManagedObjectReference filterSpecRef = vimPort.createFilter(serviceContent.getPropertyCollector(), spec, true);

        boolean reached = false;

        UpdateSet updateset = null;
        List<PropertyFilterUpdate> filtupary = null;
        List<ObjectUpdate> objupary = null;
        List<PropertyChange> propchgary = null;
        String stateVal = null;
        while (!reached) {
            try {
                updateset = vimPort.waitForUpdates(serviceContent.getPropertyCollector(), version);
            } catch (InvalidCollectorVersionFaultMsg e) {
                throw new RuntimeFaultFaultMsg("Logic error", null, e);
            }
            if (updateset == null || updateset.getFilterSet() == null) {
                continue;
            }
            version = updateset.getVersion();

            // Make this code more general purpose when PropCol changes later.
            filtupary = updateset.getFilterSet();

            for (PropertyFilterUpdate filtup : filtupary) {
                objupary = filtup.getObjectSet();
                for (ObjectUpdate objup : objupary) {
                    if (objup.getKind() == ObjectUpdateKind.MODIFY || objup.getKind() == ObjectUpdateKind.ENTER
                            || objup.getKind() == ObjectUpdateKind.LEAVE) {
                        propchgary = objup.getChangeSet();
                        for (PropertyChange propchg : propchgary) {
                            updateValues(endWaitProps, endVals, propchg);
                            updateValues(filterProps, filterVals, propchg);
                        }
                    }
                }
            }

            Object expctdval = null;
            // Check if the expected values have been reached and exit the loop
            // if done.
            // Also exit the WaitForUpdates loop if this is the case.
            for (int chgi = 0; chgi < endVals.length && !reached; chgi++) {
                for (int vali = 0; vali < expectedVals[chgi].length && !reached; vali++) {
                    expctdval = expectedVals[chgi][vali];
                    if (endVals[chgi] == null) {
                        // Do Nothing
                    } else if (endVals[chgi].toString().contains("val: null")) {
                        // Due to some issue in JAX-WS De-serialization getting the information from the nodes
                        Element stateElement = (Element) endVals[chgi];
                        if (stateElement != null && stateElement.getFirstChild() != null) {
                            stateVal = stateElement.getFirstChild().getTextContent();
                            reached = expctdval.toString().equalsIgnoreCase(stateVal) || reached;
                        }
                    } else {
                        expctdval = expectedVals[chgi][vali];
                        reached = expctdval.equals(endVals[chgi]) || reached;
                        stateVal = "filtervals";
                    }
                }
            }
        }

        // Destroy the filter when we are done.
        vimPort.destroyPropertyFilter(filterSpecRef);
        Object[] retVal = null;
        if (stateVal != null) {
            if (stateVal.equalsIgnoreCase("ready")) {
                retVal = new Object[] { HttpNfcLeaseState.READY };
            }
            if (stateVal.equalsIgnoreCase("error")) {
                retVal = new Object[] { HttpNfcLeaseState.ERROR };
            }
            if (stateVal.equals("filtervals")) {
                retVal = filterVals;
            }
        } else {
            retVal = new Object[] { HttpNfcLeaseState.ERROR };
        }
        return retVal;
    }

    /**
     * This method returns a boolean value specifying whether the Task is succeeded or failed.
     * 
     * @param task ManagedObjectReference representing the Task.
     * @throws RuntimeFaultFaultMsg
     * @throws InvalidPropertyFaultMsg
     * @throws RuntimeException
     */
    private void getTaskResultAfterDone(ManagedObjectReference task)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg, RuntimeException {
        logger.finest("Waiting for " + task.getType() + " val " + task.getValue() + " ref:" + task.toString());
        // info has a property - state for state of the task
        Object[] result = waitForValues(task, new String[] { "info.state", "info.error" }, new String[] { "state" },
                new Object[][] { new Object[] { TaskInfoState.SUCCESS, TaskInfoState.ERROR } });
        logger.finest("Completed " + task.getType() + " val " + task.getValue() + " ref:" + task.toString()
                + " with status " + ((TaskInfoState) result[0]).toString());

        if (!result[0].equals(TaskInfoState.SUCCESS)) {
            LocalizedMethodFault lmFault = (LocalizedMethodFault) result[1];
            MethodFault fault = lmFault.getFault();
            // Handle a common case
            if (fault != null && fault instanceof InvalidArgument) {
                logger.severe("Invalid property: " + ((InvalidArgument) fault).getInvalidProperty());
            }
            throw new RuntimeException(lmFault.getLocalizedMessage());
        }
    }

    private void createSnapshot(ManagedObjectReference vmMor, String snapshotName)
            throws FileFaultFaultMsg, InvalidNameFaultMsg, InvalidStateFaultMsg, RuntimeFaultFaultMsg,
            SnapshotFaultFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg, InvalidPropertyFaultMsg {
        ManagedObjectReference taskMor = vimPort.createSnapshotTask(vmMor, snapshotName, "OCCP Admin Created", false,
                false);
        getTaskResultAfterDone(taskMor);
    }

    private interface Filter {
        public boolean match(Object other);
    }

    private ManagedObjectReference getMOREFsInFolder(ManagedObjectReference folder, String morefType,
            String objectName, Filter filter) throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        String PROP_ME_NAME = "name";
        ManagedObjectReference viewManager = serviceContent.getViewManager();
        ManagedObjectReference containerView = vimPort.createContainerView(viewManager, folder,
                Arrays.asList(morefType), true);

        // Create Property Spec
        PropertySpec propertySpec = new PropertySpec();
        propertySpec.setAll(Boolean.FALSE);
        propertySpec.setType(morefType);
        propertySpec.getPathSet().add(PROP_ME_NAME);

        TraversalSpec ts = new TraversalSpec();
        ts.setName("view");
        ts.setPath("view");
        ts.setSkip(false);
        ts.setType("ContainerView");

        // Now create Object Spec
        ObjectSpec objectSpec = new ObjectSpec();
        objectSpec.setObj(containerView);
        objectSpec.setSkip(Boolean.TRUE);
        objectSpec.getSelectSet().add(ts);

        // Create PropertyFilterSpec using the PropertySpec and ObjectPec
        // created above.
        PropertyFilterSpec propertyFilterSpec = new PropertyFilterSpec();
        propertyFilterSpec.getPropSet().add(propertySpec);
        propertyFilterSpec.getObjectSet().add(objectSpec);

        List<PropertyFilterSpec> propertyFilterSpecs = new ArrayList<PropertyFilterSpec>();
        propertyFilterSpecs.add(propertyFilterSpec);

        List<ObjectContent> oCont = vimPort.retrieveProperties(serviceContent.getPropertyCollector(),
                propertyFilterSpecs);
        if (oCont != null) {
            for (ObjectContent oc : oCont) {
                ManagedObjectReference mr = oc.getObj();
                List<DynamicProperty> listdp = oc.getPropSet();
                if (listdp != null) {
                    DynamicProperty pc = null;
                    for (int pci = 0; pci < listdp.size(); pci++) {
                        pc = listdp.get(pci);
                        if (pc != null) {
                            if (pc.getName().indexOf("name") >= 0) {
                                String vmname = (String) pc.getVal();
                                if (vmname.equals(objectName) && (filter == null || filter.match(mr))) {
                                    return mr;
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private ManagedObjectReference findVM(String searchName, boolean base)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        Filter filter = new Filter() {
            @Override
            public boolean match(Object other) {
                ManagedObjectReference omor = (ManagedObjectReference) other;
                try {
                    return isOccp(omor);
                } catch (Exception e) {
                    return false;
                }
            }
        };
        if (base) {
            // Look everywhere for a base VM
            ManagedObjectReference vmFolderRef = getMOREFsInFolder(rootRef, "Folder", "vm", null);
            return getMOREFsInFolder(vmFolderRef, "VirtualMachine", searchName, null);
        }
        // Only look in the scenario folder for others
        return getMOREFsInFolder(folderRef, "VirtualMachine", searchName, filter);
    }

    private void revertSnapshot(ManagedObjectReference vmMor, String snapshotName)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg, FileFaultFaultMsg,
            InsufficientResourcesFaultFaultMsg, InvalidStateFaultMsg, TaskInProgressFaultMsg, VmConfigFaultFaultMsg {
        ManagedObjectReference snapmor = getSnapshotReference(vmMor, snapshotName);
        if (snapmor == null) {
            throw new IllegalArgumentException();
        }
        ManagedObjectReference taskMor = vimPort.revertToSnapshotTask(snapmor, null, true);
        getTaskResultAfterDone(taskMor);
    }

    private ManagedObjectReference getSnapshotReference(ManagedObjectReference vmmor, String snapName)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        VirtualMachineSnapshotInfo snapInfo = (VirtualMachineSnapshotInfo) getEntityProp(vmmor, "snapshot");
        ManagedObjectReference snapmor = null;
        if (snapInfo != null) {
            List<VirtualMachineSnapshotTree> listvmst = snapInfo.getRootSnapshotList();
            snapmor = traverseSnapshotInTree(listvmst, snapName);
        }
        return snapmor;
    }

    private void printSoapFaultException(SOAPFaultException sfe) {
        if (sfe.getFault().hasDetail()) {
            logger.severe("SOAP Fault:" + sfe.getFault().getDetail().getFirstChild().getLocalName());
        }
        if (sfe.getFault().getFaultString() != null) {
            logger.severe("SOAP Fault Message: " + sfe.getFault().getFaultString());
        }
    }

    /**
     * Retrieve contents for a single object registered with the service.
     * 
     * @param mobj Managed Object Reference to get contents for
     * @param properties names of properties of object to retrieve
     * @return retrieved object contents
     */
    private ObjectContent[] getObjectProperties(ManagedObjectReference mobj, String[] properties) {
        if (mobj == null) {
            return null;
        }

        PropertyFilterSpec spec = new PropertyFilterSpec();
        spec.getPropSet().add(new PropertySpec());
        if ((properties == null || properties.length == 0)) {
            spec.getPropSet().get(0).setAll(Boolean.TRUE);
        } else {
            spec.getPropSet().get(0).setAll(Boolean.FALSE);
            spec.getPropSet().get(0).getPathSet().addAll(Arrays.asList(properties));
        }
        spec.getPropSet().get(0).setType(mobj.getType());
        spec.getObjectSet().add(new ObjectSpec());
        spec.getObjectSet().get(0).setObj(mobj);
        spec.getObjectSet().get(0).setSkip(Boolean.FALSE);
        List<PropertyFilterSpec> listpfs = new ArrayList<PropertyFilterSpec>(1);
        listpfs.add(spec);
        List<ObjectContent> listobjcont = retrievePropertiesAllObjects(listpfs);
        return listobjcont.toArray(new ObjectContent[listobjcont.size()]);
    }

    /**
     * Determines of a method 'methodName' exists for the Object 'obj'.
     * 
     * @param obj The Object to check
     * @param methodName The method name
     * @param parameterTypes Array of Class objects for the parameter types
     * @return true if the method exists, false otherwise
     */
    @SuppressWarnings("rawtypes")
    private boolean methodExists(Object obj, String methodName, Class[] parameterTypes) {
        boolean exists = false;
        try {
            Method method = obj.getClass().getMethod(methodName, parameterTypes);
            if (method != null) {
                exists = true;
            }
        } catch (SOAPFaultException sfe) {
            printSoapFaultException(sfe);
        } catch (Exception e) {
            logger.log(Level.FINEST, "Unexpected exception", e);
        }
        return exists;
    }

    private Object getDynamicProperty(ManagedObjectReference mor, String propertyName) {
        ObjectContent[] objContent = getObjectProperties(mor, new String[] { propertyName });

        Object propertyValue = null;
        if (objContent != null && objContent.length > 0) {
            List<DynamicProperty> listdp = objContent[0].getPropSet();
            if (listdp != null) {
                /*
                 * Check the dynamic property for ArrayOfXXX object
                 */
                Object dynamicPropertyVal = listdp.get(0).getVal();
                String dynamicPropertyName = dynamicPropertyVal.getClass().getName();
                if (dynamicPropertyName.indexOf("ArrayOf") != -1) {
                    String methodName = dynamicPropertyName.substring(dynamicPropertyName.indexOf("ArrayOf")
                            + "ArrayOf".length(), dynamicPropertyName.length());
                    /*
                     * If object is ArrayOfXXX object, then get the XXX[] by invoking getXXX() on the object. For Ex:
                     * ArrayOfManagedObjectReference.getManagedObjectReference() returns ManagedObjectReference[] array.
                     */
                    if (methodExists(dynamicPropertyVal, "get" + methodName, null)) {
                        methodName = "get" + methodName;
                    } else {
                        /*
                         * Construct methodName for ArrayOf primitive types Ex: For ArrayOfInt, methodName is get_int
                         */
                        methodName = "get_" + methodName.toLowerCase();
                    }
                    Method getMorMethod;
                    try {
                        getMorMethod = dynamicPropertyVal.getClass().getDeclaredMethod(methodName, (Class[]) null);
                        propertyValue = getMorMethod.invoke(dynamicPropertyVal, (Object[]) null);
                    } catch (NoSuchMethodException | SecurityException | IllegalAccessException
                            | IllegalArgumentException | InvocationTargetException e) {
                        logger.log(Level.SEVERE, "Internal error", e);
                    }
                } else if (dynamicPropertyVal.getClass().isArray()) {
                    /*
                     * Handle the case of an unwrapped array being deserialized.
                     */
                    propertyValue = dynamicPropertyVal;
                } else {
                    propertyValue = dynamicPropertyVal;
                }
            }
        }
        return propertyValue;
    }

    private String getFullPath(ManagedObjectReference child) {
        if (child != null) {
            String namePart = (String) getDynamicProperty(child, "name");
            try {
                ManagedObjectReference parent = (ManagedObjectReference) getDynamicProperty(child, "parent");
                return getFullPath(parent) + "/" + namePart;
            } catch (IndexOutOfBoundsException e) {
            } // expected
              // The paths it wants doesn't start with this
            if (namePart != null && namePart.equals("Datacenters")) {
                return "";
            }
            return namePart;
        }
        return "";
    }

    private String getDatastorePath(ManagedObjectReference vm) throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        VirtualMachineFileLayoutEx layout = (VirtualMachineFileLayoutEx) getEntityProp(vm, "layoutEx");
        List<VirtualMachineFileLayoutExFileInfo> files = layout.getFile();
        if (!files.isEmpty()) {
            return files.get(0).getName();
        }
        return null;
    }

    private boolean addVirtualSwitch(String virtualswitchid) {
        try {
            HostConfigManager configMgr = (HostConfigManager) getEntityProp(hostmor, "configManager");
            ManagedObjectReference nwSystem = configMgr.getNetworkSystem();

            // Look to see if it already exists
            @SuppressWarnings("unchecked")
            List<HostVirtualSwitch> switches = (List<HostVirtualSwitch>) getDynamicProperty(nwSystem,
                    "networkInfo.vswitch");
            if (switches != null) {
                for (HostVirtualSwitch sw : switches) {
                    if (sw.getName().equals(virtualswitchid)) {
                        return true;
                    }
                }

            }

            HostVirtualSwitchSpec spec = new HostVirtualSwitchSpec();
            spec.setNumPorts(20);
            vimPort.addVirtualSwitch(nwSystem, virtualswitchid, spec);
            return true;
        } catch (AlreadyExistsFaultMsg ex) {
            // Shouldn't happen, but could happen from a race condition
            logger.log(Level.FINEST, "Switch " + virtualswitchid + " already exists");
            return true;
        } catch (HostConfigFaultFaultMsg | ResourceInUseFaultMsg ex) {
            logger.log(Level.FINEST, "Failed: Configuration failure trying to setup " + virtualswitchid, ex);
        } catch (SOAPFaultException sfe) {
            printSoapFaultException(sfe);
        } catch (Exception ex) {
            logger.log(Level.FINEST, "Failed adding switch: " + virtualswitchid, ex);
        }
        return false;
    }

    private boolean addVirtualSwitchPortGroup(String virtualswitchid, String netName) {
        // Always create us a switch to work in
        addVirtualSwitch(virtualswitchid);
        try {
            HostConfigManager configMgr = (HostConfigManager) getEntityProp(hostmor, "configManager");
            ManagedObjectReference nwSystem = configMgr.getNetworkSystem();

            HostPortGroupSpec portgrp = new HostPortGroupSpec();
            portgrp.setName(netName);
            portgrp.setVswitchName(virtualswitchid);
            HostNetworkPolicy policy = new HostNetworkPolicy();
            HostNetworkSecurityPolicy security = new HostNetworkSecurityPolicy();
            security.setAllowPromiscuous(true);
            policy.setSecurity(security);
            portgrp.setPolicy(policy);
            // See if we are only updating the policy
            if (_networkExists(netName, false)) {
                vimPort.updatePortGroup(nwSystem, netName, portgrp);
            } else {
                vimPort.addPortGroup(nwSystem, portgrp);
            }
            return true;
        } catch (AlreadyExistsFaultMsg ex) {
            logger.finest("Already exists creating: " + virtualswitchid + "/" + netName);
        } catch (HostConfigFaultFaultMsg e) {
            logger.log(Level.SEVERE, "Configuration failure: " + e.getLocalizedMessage(), e);
        } catch (SOAPFaultException sfe) {
            printSoapFaultException(sfe);
        } catch (InvalidPropertyFaultMsg e) {
            logger.log(Level.SEVERE, "Invalid Property", e);
        } catch (RuntimeFaultFaultMsg e) {
            logger.log(Level.SEVERE, "Runtime Fault", e);
        } catch (NotFoundFaultMsg e) {
            logger.log(Level.SEVERE, "Not found: " + e.getLocalizedMessage(), e);
        }
        return false;
    }

    // Not yet used: For cleanup
    @SuppressWarnings("unused")
    private void removeVirtualSwitch(String virtualswitchid) throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        try {
            HostConfigManager configMgr = (HostConfigManager) getEntityProp(hostmor, "configManager");
            ManagedObjectReference nwSystem = configMgr.getNetworkSystem();
            vimPort.removeVirtualSwitch(nwSystem, virtualswitchid);
            logger.finest("Successful removing : " + virtualswitchid);
        } catch (HostConfigFaultFaultMsg ex) {
            logger.log(Level.SEVERE, "Configuration failure", ex);
        } catch (NotFoundFaultMsg ex) {
            logger.finest("Not found removing : " + virtualswitchid);
        } catch (ResourceInUseFaultMsg ex) {
            logger.finest("In use removing : " + virtualswitchid);
        } catch (RuntimeFaultFaultMsg ex) {
            logger.log(Level.WARNING, "Configuration failure", ex);
        } catch (SOAPFaultException sfe) {
            printSoapFaultException(sfe);
        }
    }

    private boolean checkNetworkPolicy(String netName) throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        HostConfigManager cfgmor = (HostConfigManager) getEntityProp(hostmor, "configManager");
        ManagedObjectReference nwsys = cfgmor.getNetworkSystem();
        HostNetworkInfo netinfo = (HostNetworkInfo) getEntityProp(nwsys, "networkInfo");
        List<HostPortGroup> portgroups = netinfo.getPortgroup();
        for (HostPortGroup group : portgroups) {
            HostPortGroupSpec spec = group.getSpec();
            if (spec.getName().equals(netName)) {
                HostNetworkPolicy policy = spec.getPolicy();
                HostNetworkSecurityPolicy security = policy.getSecurity();
                if (security != null) {
                    Boolean promisc = security.isAllowPromiscuous();
                    if (promisc != null) {
                        return promisc.booleanValue();
                    }
                }
                return false;
            }
        }
        return false;
    }

    private boolean _networkExists(String virtualswitchid, boolean checkPolicy)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        try {
            ArrayOfManagedObjectReference aomor = (ArrayOfManagedObjectReference) getEntityProp(hostmor, "network");
            List<ManagedObjectReference> networks = aomor.getManagedObjectReference();

            for (ManagedObjectReference net : networks) {
                // String name = (String) getDynamicProperty(net, "name");
                String testName = (String) getEntityProp(net, "name");
                if (virtualswitchid.equals(testName)) {
                    if (checkPolicy) {
                        /* Need to check if promisc mode is allowed, since it is required for VPN system */
                        return checkNetworkPolicy(virtualswitchid);
                    }
                    return true;
                }
            }
        } catch (SOAPFaultException sfe) {
            printSoapFaultException(sfe);
        } catch (Exception e) {
            throw e;
        }
        return false;
    }

    // Not as good, but should work on ESXi w/o vSphere
    // Copies entire folder of VM. Assumes all necessary files are in that folder
    private boolean fileCloneVM(ManagedObjectReference vm, String cloneName) throws Exception {
        String vmPathFileName = getDatastorePath(vm);
        // Format: [datastore] vmname/vmname.vmx
        if (vmPathFileName == null) {
            return false;
        }
        // Copy the entire folder
        String vmPathFolderName = vmPathFileName.substring(0, vmPathFileName.lastIndexOf("/"));
        // Use the same datastore, for now
        String parentName = vmPathFolderName.substring(0, vmPathFolderName.indexOf(" "));
        // datastore and file path separated by space, not /
        String clonePath = parentName + " " + cloneName;
        // Less than ideal: has old file names
        String oldVmxName = vmPathFileName.substring(vmPathFileName.lastIndexOf("/") + 1);

        // copyDatastoreFileTask arg 1
        ManagedObjectReference fm = serviceContent.getFileManager();

        // Locate the datastore we want; making some assumptions because we know this is esxi
        // copyDatastoreFileTask args 3, 5
        ManagedObjectReference vmDCRef = vimPort.findChild(serviceContent.getSearchIndex(), rootRef, "ha-datacenter");

        ManagedObjectReference copyTask = vimPort.copyDatastoreFileTask(fm, vmPathFolderName, vmDCRef, clonePath,
                vmDCRef, false);
        getTaskResultAfterDone(copyTask);

        // registerVMTask arg 1
        ManagedObjectReference folderRef = vimPort.findChild(serviceContent.getSearchIndex(), vmDCRef, "vm");
        if (folderRef == null) {
            logger.severe("Where are my VMs?");
            throw new RuntimeException("Hypervisor misconfiguration");
        }

        // registerVMTask arg 5
        // Documentation says pool is optional; ESXi doesn't agree. Copy from original
        logger.finest(clonePath + "/" + oldVmxName + " -> " + cloneName);
        ManagedObjectReference pool = (ManagedObjectReference) getDynamicProperty(vm, "resourcePool");

        ManagedObjectReference task = vimPort.registerVMTask(folderRef, clonePath + "/" + oldVmxName, cloneName, false,
                pool, null);
        getTaskResultAfterDone(task);

        // Get a reference to the new VM so we can update it
        ManagedObjectReference newVM = (ManagedObjectReference) getEntityProp(task, "info.result");

        /*
         * @formatter:off
         * Now that it is registered, we need to reset the UUID and MAC address
         * VirtualMachineConfigSpec.uuid = newid
         * VirtualMachineConfigSpec.deviceChange (VirtualDeviceConfigSpec)
         * .device = VirtualEthernetCard
         * .opertaion = edit
         */
        // @formatter:on
        VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
        spec.setUuid(UUID.randomUUID().toString());
        // Same as: assignVMGroup(cloneName, newVM);
        OptionValue ov = new OptionValue();
        ov.setKey("occp.group");
        ov.setValue(groupName);
        spec.getExtraConfig().add(ov);

        ArrayList<VirtualEthernetCard> cards = getVMNics(newVM);
        ArrayList<VirtualDeviceConfigSpec> devices = new ArrayList<VirtualDeviceConfigSpec>();
        for (VirtualEthernetCard card : cards) {
            VirtualDeviceConfigSpec nic = new VirtualDeviceConfigSpec();
            nic.setOperation(VirtualDeviceConfigSpecOperation.EDIT);
            // We don't care what the address is, as long as it is unique
            card.setMacAddress(null);
            card.setAddressType("Generated");
            nic.setDevice(card);
            devices.add(nic);
        }
        spec.getDeviceChange().addAll(devices);
        vimPort.reconfigVMTask(newVM, spec);
        assignVMGroup(cloneName, newVM);
        return true;
    }

    // Only works with datacenter (vSphere), not with raw ESXi
    @SuppressWarnings("unused")
    private boolean cloneVM(ManagedObjectReference vm, String cloneName) throws Exception {
        // Clone function takes the path name to the VM
        String vmPathName = getFullPath(vm);
        if (vmPathName == null) {
            // Only from runtime error, already reported
            return false;
        }

        ManagedObjectReference vmRef = vimPort.findByInventoryPath(serviceContent.getSearchIndex(), vmPathName);

        VirtualMachineCloneSpec cloneSpec = new VirtualMachineCloneSpec();
        VirtualMachineRelocateSpec relocSpec = new VirtualMachineRelocateSpec();
        cloneSpec.setLocation(relocSpec);
        cloneSpec.setPowerOn(false);
        cloneSpec.setTemplate(false);

        try {
            logger.finest("Starting clone task for " + cloneName);
            ManagedObjectReference cloneTask = vimPort.cloneVMTask(vmRef, folderRef, cloneName, cloneSpec);
            getTaskResultAfterDone(cloneTask);
            ManagedObjectReference newVM = (ManagedObjectReference) getEntityProp(cloneTask, "info.result");
            assignVMGroup(cloneName, newVM);
        } catch (SOAPFaultException sfe) {
            printSoapFaultException(sfe);
            throw sfe;
        } catch (Exception e) {
            throw e;
        }
        return true;
    }

    // Only works with vCenter, not with raw ESXi
    // Synchronized prevents likely race condition; this operation should be quick
    private synchronized boolean linkedCloneVM(ManagedObjectReference vm, String cloneName, String snapshotBase)
            throws Exception {
        // Clone function takes the path name to the VM
        String vmPathName = getFullPath(vm);
        if (vmPathName == null) {
            // Only from runtime error, already reported
            return false;
        }

        ManagedObjectReference vmRef = vimPort.findByInventoryPath(serviceContent.getSearchIndex(), vmPathName);

        ManagedObjectReference snapMOR = null;
        synchronized (this) {
            snapMOR = getSnapshotReference(vm, snapshotBase);
            if (snapMOR == null) {
                this.createSnapshot(vm, snapshotBase);
                snapMOR = getSnapshotReference(vm, snapshotBase);
            }
        }

        VirtualMachineCloneSpec cloneSpec = new VirtualMachineCloneSpec();
        VirtualMachineRelocateSpec relocSpec = new VirtualMachineRelocateSpec();
        relocSpec.setDiskMoveType(VirtualMachineRelocateDiskMoveOptions.CREATE_NEW_CHILD_DISK_BACKING.value());
        cloneSpec.setLocation(relocSpec);
        cloneSpec.setPowerOn(false);
        cloneSpec.setTemplate(false);
        cloneSpec.setSnapshot(snapMOR);

        try {
            logger.finest("Starting clone task for " + cloneName);
            ManagedObjectReference cloneTask = vimPort.cloneVMTask(vmRef, folderRef, cloneName, cloneSpec);
            getTaskResultAfterDone(cloneTask);
            ManagedObjectReference newVM = (ManagedObjectReference) getEntityProp(cloneTask, "info.result");
            assignVMGroup(cloneName, newVM);
        } catch (SOAPFaultException sfe) {
            printSoapFaultException(sfe);
            throw sfe;
        } catch (Exception e) {
            throw e;
        }
        return true;
    }

    /**
     * Method to retrieve properties of a ManagedObjectReference
     * 
     * @param entityMor ManagedObjectReference of the entity
     * @param prop property to be looked up
     * @return Map of the property name and its corresponding value
     * @throws InvalidPropertyFaultMsg If a property does not exist
     * @throws RuntimeFaultFaultMsg
     */
    private Object getEntityProp(ManagedObjectReference entityMor, String prop)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {

        // Create Property Spec
        PropertySpec propertySpec = new PropertySpec();
        propertySpec.setAll(false);
        propertySpec.setType(entityMor.getType());
        // propertySpec.getPathSet().addAll(Arrays.asList(props));
        propertySpec.getPathSet().add(prop);

        // Now create Object Spec
        ObjectSpec objectSpec = new ObjectSpec();
        objectSpec.setObj(entityMor);

        // Create PropertyFilterSpec using the PropertySpec and ObjectPec
        // created above.
        PropertyFilterSpec propertyFilterSpec = new PropertyFilterSpec();
        propertyFilterSpec.getPropSet().add(propertySpec);
        propertyFilterSpec.getObjectSet().add(objectSpec);

        List<PropertyFilterSpec> propertyFilterSpecs = new ArrayList<PropertyFilterSpec>();
        propertyFilterSpecs.add(propertyFilterSpec);

        RetrieveResult rslts = vimPort.retrievePropertiesEx(serviceContent.getPropertyCollector(), propertyFilterSpecs,
                new RetrieveOptions());
        List<ObjectContent> listobjcontent = new ArrayList<ObjectContent>();
        if (rslts != null && rslts.getObjects() != null && !rslts.getObjects().isEmpty()) {
            listobjcontent.addAll(rslts.getObjects());
        }
        String token = null;
        if (rslts != null && rslts.getToken() != null) {
            token = rslts.getToken();
        }
        while (token != null && !token.isEmpty()) {
            rslts = vimPort.continueRetrievePropertiesEx(serviceContent.getPropertyCollector(), token);
            token = null;
            if (rslts != null) {
                token = rslts.getToken();
                if (rslts.getObjects() != null && !rslts.getObjects().isEmpty()) {
                    listobjcontent.addAll(rslts.getObjects());
                }
            }
        }
        for (ObjectContent oc : listobjcontent) {
            List<DynamicProperty> dps = oc.getPropSet();
            if (dps != null && dps.size() == 1) {
                return dps.get(0).getVal();
            }
        }
        return null;
    }

    private ManagedObjectReference traverseSnapshotInTree(List<VirtualMachineSnapshotTree> snapTree, String findName) {
        ManagedObjectReference snapmor = null;
        if (snapTree == null) {
            return snapmor;
        }
        for (VirtualMachineSnapshotTree node : snapTree) {
            if (findName != null && node.getName().equalsIgnoreCase(findName)) {
                return node.getSnapshot();
            }
            // Recurse
            List<VirtualMachineSnapshotTree> listvmst = node.getChildSnapshotList();
            List<VirtualMachineSnapshotTree> childTree = listvmst;
            snapmor = traverseSnapshotInTree(childTree, findName);
            // if findName is null, return last snapshot
            if (findName == null && childTree.isEmpty()) {
                return node.getSnapshot();
            }
        }
        return snapmor;
    }

    private OvfCreateImportSpecParams createImportSpecParams(ManagedObjectReference host, String newVmName) {
        OvfCreateImportSpecParams importSpecParams = new OvfCreateImportSpecParams();
        importSpecParams.setHostSystem(host);
        importSpecParams.setLocale("");
        importSpecParams.setEntityName(newVmName);
        importSpecParams.setDeploymentOption("");
        return importSpecParams;
    }

    private boolean getVMDKFile(boolean put, String fileName, InputStream fileStream, String uri, long diskCapacity,
            HttpNfcLeaseExtender extender) throws IOException {
        HttpsURLConnection conn = null;
        BufferedOutputStream bos = null;

        int bytesRead, bytesAvailable, bufferSize;
        byte[] buffer;
        int maxBufferSize = 64 * 1024;

        try {
            logger.finest("Destination host URL: " + uri);
            HostnameVerifier hv = new HostnameVerifier() {
                @Override
                public boolean verify(String urlHostName, SSLSession session) {
                    if (!urlHostName.equals(session.getPeerHost())) {
                        logger.warning("Warning: URL Host: " + urlHostName + " vs. " + session.getPeerHost());
                    }
                    return true;
                }
            };
            HttpsURLConnection.setDefaultHostnameVerifier(hv);
            URL vmdkUrl = new URL(uri);
            conn = (HttpsURLConnection) vmdkUrl.openConnection();

            // Maintain session
            @SuppressWarnings("unchecked")
            List<String> cookies = (List<String>) headers.get("Set-cookie");
            String cookieValue = cookies.get(0);
            StringTokenizer tokenizer = new StringTokenizer(cookieValue, ";");
            cookieValue = tokenizer.nextToken();
            String path = "$" + tokenizer.nextToken();
            String cookie = "$Version=\"1\"; " + cookieValue + "; " + path;

            // set the cookie in the new request header
            Map<String, List<String>> map = new HashMap<String, List<String>>();
            map.put("Cookie", Collections.singletonList(cookie));
            ((BindingProvider) vimPort).getRequestContext().put(MessageContext.HTTP_REQUEST_HEADERS, map);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setChunkedStreamingMode(maxBufferSize);
            if (put) {
                conn.setRequestMethod("PUT");
                conn.setRequestProperty("Overwrite", "t");
                logger.finest("HTTP method: PUT");
            } else {
                conn.setRequestMethod("POST");
                logger.finest("HTTP method: POST");
            }
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.setRequestProperty("Content-Type", "application/x-vnd.vmware-streamVmdk");
            conn.setRequestProperty("Content-Length", String.valueOf(diskCapacity));
            conn.setRequestProperty("Expect", "100-continue");
            bos = new BufferedOutputStream(conn.getOutputStream());
            logger.fine("Local file path: " + fileName);
            BufferedInputStream bis = new BufferedInputStream(fileStream);
            bytesAvailable = bis.available();
            logger.finest("vmdk available bytes: " + bytesAvailable);
            bufferSize = Math.min(bytesAvailable, maxBufferSize);
            buffer = new byte[bufferSize];
            bytesRead = bis.read(buffer, 0, bufferSize);
            long bytesWrote = bytesRead;
            extender.currentFile = fileName;
            extender.TOTAL_BYTES_WRITTEN += bytesRead;
            while (bytesRead >= 0) {
                bos.write(buffer, 0, bufferSize);
                bos.flush();
                bytesAvailable = bis.available();
                bufferSize = Math.min(bytesAvailable, maxBufferSize);
                bytesWrote += bufferSize;
                extender.TOTAL_BYTES_WRITTEN += bufferSize;
                buffer = null;
                buffer = new byte[bufferSize];
                bytesRead = bis.read(buffer, 0, bufferSize);
                if ((bytesRead == 0) && (bytesWrote >= diskCapacity)) {
                    bytesRead = -1;
                }
            }
            // Read server response
            DataInputStream dis = new DataInputStream(conn.getInputStream());
            dis.close();
            logger.fine("Writing vmdk to the output stream done:" + fileName);
            bis.close();
        } finally {
            try {
                if (bos != null) {
                    bos.flush();
                    bos.close();
                }
                if (conn != null) {
                    conn.disconnect();
                }
            } catch (SOAPFaultException sfe) {
                printSoapFaultException(sfe);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to close handles or disconnect", e);
            }
        }
        return true;
    }

    private String getOvfDescriptorFromLocal(String ovfDescriptorUrl) throws IOException {
        StringBuffer strContent = new StringBuffer("");
        int x;
        // Given filename is relative to Scenario directory
        ovfDescriptorUrl = OccpAdmin.scenarioBaseDir.resolve(ovfDescriptorUrl).toAbsolutePath().toString();
        if (ovfDescriptorUrl.endsWith("ovf")) {
            try (InputStream fis = new FileInputStream(ovfDescriptorUrl)) {
                while ((x = fis.read()) != -1) {
                    strContent.append((char) x);
                }
            } catch (FileNotFoundException e) {
                logger.severe("Invalid local file path" + ovfDescriptorUrl);
                return null;
            }
        } else if (ovfDescriptorUrl.endsWith("ova")) {
            try (TarArchiveInputStream ova = new TarArchiveInputStream(new FileInputStream(ovfDescriptorUrl))) {
                TarArchiveEntry entry = ova.getNextTarEntry();
                while (entry != null) {
                    if (entry.isFile() && entry.getName().endsWith("ovf")) {
                        byte[] buf = new byte[(int) entry.getSize()];
                        int bytesRead = ova.read(buf, 0, (int) entry.getSize());
                        if (bytesRead != entry.getSize()) {
                            logger.severe("Corrupt OVA file: " + ovfDescriptorUrl);
                            return null;
                        }
                        strContent.append(new String(buf));
                    }
                    entry = ova.getNextTarEntry();
                }
            } catch (FileNotFoundException e) {
                logger.severe("Invalid tar archive extracting file from " + ovfDescriptorUrl);
                return null;
            }
        } else {
            logger.severe("Don't recognize file for import" + ovfDescriptorUrl);
            return null;
        }

        return strContent + "";
    }

    private boolean importVApp(String vmName, String localPath) throws Exception {
        ManagedObjectReference httpNfcLease = null;
        try {
            ManagedObjectReference rpMor = null;
            ManagedObjectReference parent = (ManagedObjectReference) getEntityProp(hostmor, "parent");
            rpMor = (ManagedObjectReference) getEntityProp(parent, "resourcePool");

            OvfCreateImportSpecParams importSpecParams = createImportSpecParams(hostmor, vmName);
            String ovfDescriptor = getOvfDescriptorFromLocal(localPath);
            if (ovfDescriptor == null || ovfDescriptor.isEmpty()) {
                throw new IllegalArgumentException("Missing or empty VM description");
            }
            // Ease import from VirtualBox files by ignoring unknown values
            importSpecParams.getImportOption().add("lax");
            OvfParseDescriptorParams parseParams = new OvfParseDescriptorParams();
            parseParams.setLocale("");
            parseParams.setDeploymentOption("");
            parseParams.getImportOption().add("lax");
            OvfParseDescriptorResult parseResult = vimPort.parseDescriptor(serviceContent.getOvfManager(),
                    ovfDescriptor, parseParams);
            List<OvfNetworkMapping> networkMapping = new ArrayList<>();
            List<OvfNetworkInfo> importNets = parseResult.getNetwork();

            // Find setup network MOR
            ArrayOfManagedObjectReference aomor = (ArrayOfManagedObjectReference) getEntityProp(hostmor, "network");
            List<ManagedObjectReference> networks = aomor.getManagedObjectReference();
            ManagedObjectReference setupNetwork = null;
            for (ManagedObjectReference net : networks) {
                String testName = (String) getEntityProp(net, "name");
                if (OccpAdmin.setupNetworkName.equals(testName)) {
                    setupNetwork = net;
                    break;
                }
            }
            // Default to the setup network
            for (OvfNetworkInfo importNet : importNets) {
                OvfNetworkMapping mapping = new OvfNetworkMapping();
                mapping.setName(importNet.getName());
                mapping.setNetwork(setupNetwork);
                networkMapping.add(mapping);
            }
            importSpecParams.getNetworkMapping().addAll(networkMapping);

            OvfCreateImportSpecResult ovfImportResult = vimPort.createImportSpec(serviceContent.getOvfManager(),
                    ovfDescriptor, rpMor, dsMor, importSpecParams);
            List<LocalizedMethodFault> errors = ovfImportResult.getError();
            for (LocalizedMethodFault error : errors) {
                logger.severe(error.getLocalizedMessage());
            }
            for (LocalizedMethodFault warn : ovfImportResult.getWarning()) {
                logger.warning(warn.getLocalizedMessage());
            }
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("Hypervisor rejects VM description");
            }
            List<OvfFileItem> fileItemArr = ovfImportResult.getFileItem();
            Long TOTAL_BYTES = (long) 0;
            if (fileItemArr != null) {
                for (OvfFileItem fi : fileItemArr) {
                    if (fi.getSize() > 0) {
                        TOTAL_BYTES += fi.getSize();
                    } else {
                        // Assuming it's OVA
                        TarArchiveInputStream ova = new TarArchiveInputStream(new FileInputStream(
                                OccpAdmin.scenarioBaseDir.resolve(localPath).toString()));
                        TarArchiveEntry entry = ova.getNextTarEntry();
                        while (entry != null) {
                            if (entry.isFile() && entry.getName().equalsIgnoreCase(fi.getPath())) {
                                TOTAL_BYTES += entry.getSize();
                                break;
                            }
                            entry = ova.getNextTarEntry();
                        }
                        ova.close();
                    }
                }
            } else {
                throw new IllegalArgumentException("Not importing VM with no attached media");
            }
            logger.fine("Uploading: " + TOTAL_BYTES);
            httpNfcLease = vimPort.importVApp(rpMor, ovfImportResult.getImportSpec(), folderRef, hostmor);
            Object[] result = waitForValues(httpNfcLease, new String[] { "state" }, new String[] { "state" },
                    new Object[][] { new Object[] { HttpNfcLeaseState.READY, HttpNfcLeaseState.ERROR } });
            if (result[0].equals(HttpNfcLeaseState.READY)) {
                logger.finest("HttpNfcLeaseState: " + result[0]);
                HttpNfcLeaseInfo httpNfcLeaseInfo = (HttpNfcLeaseInfo) getEntityProp(httpNfcLease, "info");
                HttpNfcLeaseExtender leaseExtender = new HttpNfcLeaseExtender(httpNfcLease, vimPort, vmName,
                        TOTAL_BYTES, true);
                Thread t = new Thread(leaseExtender);
                t.start();
                List<HttpNfcLeaseDeviceUrl> deviceUrlArr = httpNfcLeaseInfo.getDeviceUrl();
                for (HttpNfcLeaseDeviceUrl deviceUrl : deviceUrlArr) {
                    String deviceKey = deviceUrl.getImportKey();
                    for (OvfFileItem ovfFileItem : fileItemArr) {
                        if (deviceKey.equals(ovfFileItem.getDeviceId())) {
                            logger.finest("Import key: " + deviceKey);
                            logger.finest("OvfFileItem device id: " + ovfFileItem.getDeviceId());
                            logger.finest("HTTP Post file: " + ovfFileItem.getPath());
                            Path filePath = FileSystems.getDefault().getPath(localPath);
                            String absoluteFile = filePath.toAbsolutePath().toString();
                            logger.finest("Absolute path: " + absoluteFile);
                            InputStream vmdkFile = null;
                            TarArchiveInputStream ova = null;
                            if (localPath.endsWith(".ova")) {
                                ova = new TarArchiveInputStream(new FileInputStream(OccpAdmin.scenarioBaseDir.resolve(
                                        localPath).toString()));
                                TarArchiveEntry entry = ova.getNextTarEntry();
                                while (entry != null) {
                                    if (entry.isFile() && entry.getName().equalsIgnoreCase(ovfFileItem.getPath())) {
                                        vmdkFile = ova;
                                        break;
                                    }
                                    entry = ova.getNextTarEntry();
                                }
                            } else {
                                vmdkFile = new FileInputStream(absoluteFile);
                            }
                            try {
                                getVMDKFile(ovfFileItem.isCreate(), absoluteFile, vmdkFile,
                                        deviceUrl.getUrl().replace("*", host), ovfFileItem.getSize(), leaseExtender);
                            } catch (Exception e) {
                                logger.severe("Failed uploading " + ovfFileItem.getPath());
                                // re-throw to hit abort lease
                                throw e;
                            }
                            logger.fine("Completed uploading " + ovfFileItem.getPath());
                        }
                    }
                }
                leaseExtender.vmdkFlag = true;
                t.interrupt();
                vimPort.httpNfcLeaseProgress(httpNfcLease, 100);
                vimPort.httpNfcLeaseComplete(httpNfcLease);
            } else {
                logger.severe("HttpNfcLeaseState not ready");
                for (Object o : result) {
                    logger.finer("HttpNfcLeaseState: " + o);
                }
                throw new VMOperationFailedException(name, vmName, ErrorCode.IMPORT);
            }
            ManagedObjectReference newVm = getMOREFsInFolder(folderRef, "VirtualMachine", vmName, null);
            assignVMGroup(vmName, newVm);
        } catch (Exception e) {
            if (e.getClass() == SOAPFaultException.class) {
                printSoapFaultException((SOAPFaultException) e);
            }
            if (httpNfcLease != null) {
                LocalizedMethodFault fault = new LocalizedMethodFault();
                fault.setFault(new OvfImportFailed());
                try {
                    vimPort.httpNfcLeaseAbort(httpNfcLease, fault);
                } catch (InvalidStateFaultMsg | RuntimeFaultFaultMsg | TimedoutFaultMsg e1) {
                    VMOperationFailedException newEx = new VMOperationFailedException(name, vmName, ErrorCode.IMPORT, e);
                    newEx.addSuppressed(e1);
                    throw newEx;
                }
            }
            throw e;
        }
        return true;
    }

    private class HttpNfcLeaseExtender implements Runnable {
        public Long TOTAL_BYTES_WRITTEN;
        public Long TOTAL_BYTES;
        private ManagedObjectReference httpNfcLease = null;
        private VimPortType vimPort = null;
        private int progressPercent = 0;
        public boolean vmdkFlag = false;
        public boolean importing = true;
        public String vmName = null;
        public String currentFile = null;

        public HttpNfcLeaseExtender(ManagedObjectReference mor, VimPortType vimport, String vmName, Long totalBytes,
                boolean importing) {
            httpNfcLease = mor;
            vimPort = vimport;
            TOTAL_BYTES = totalBytes;
            TOTAL_BYTES_WRITTEN = Long.valueOf(0);
            this.vmName = vmName;
            this.importing = importing;
        }

        @Override
        public void run() {
            Thread.currentThread().setName("LeaseExtender " + vmName);
            while (!vmdkFlag) {
                if (TOTAL_BYTES != 0) {
                    progressPercent = (int) ((TOTAL_BYTES_WRITTEN * 100) / (TOTAL_BYTES));
                } else {
                    progressPercent = 0;
                }
                try {
                    vimPort.httpNfcLeaseProgress(httpNfcLease, progressPercent);
                    if (currentFile != null) {
                        if (importing) {
                            logger.fine("Importing " + currentFile + "; " + vmName + " import is " + progressPercent
                                    + "% complete.");
                        } else {
                            if (TOTAL_BYTES != 0) {
                                logger.fine("Exporting " + currentFile + "; " + vmName + " export is "
                                        + progressPercent + "% complete.");
                            } else {
                                logger.fine("Exporting " + currentFile + "; " + TOTAL_BYTES_WRITTEN
                                        + " bytes transferred ");
                            }
                        }
                    }
                } catch (SOAPFaultException sfe) {
                    printSoapFaultException(sfe);
                } catch (RuntimeFaultFaultMsg | TimedoutFaultMsg e) {
                    // Don't worry about it, trying again.
                } finally {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        // Expected
                    }
                }
            }
        }
    }

    private class KeepAlive implements Runnable {
        @Override
        public void run() {
            Thread.currentThread().setName("Keepalive " + getName());
            try {
                while (!Thread.interrupted()) {
                    Thread.sleep(60000);
                    serviceContent.getAbout();
                }
            } catch (InterruptedException sfe) {
                // We are terminating
            } catch (SOAPFaultException sfe) {
                printSoapFaultException(sfe);
            }
        }
    }

    private void writeVMDKFile(TarArchiveOutputStream tarFile, String url, HttpNfcLeaseExtender leaseExtender)
            throws IOException {
        URL urlStr = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) urlStr.openConnection();

        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setAllowUserInteraction(true);
        conn.connect();
        InputStream in = conn.getInputStream();
        byte[] buf = new byte[102400];
        int len = 0;
        long written = 0;
        Map<String, List<String>> connheaders = conn.getHeaderFields();
        for (Entry<String, List<String>> v : connheaders.entrySet()) {
            logger.finest("Header: " + v.getKey() + " Value:" + StringUtils.join(v.getValue(), ";"));
            String headerName = v.getKey();
            if (headerName != null && headerName.equals("Content-Length")) {
                leaseExtender.TOTAL_BYTES = Long.parseLong(v.getValue().get(0));
            }
        }

        // Reset per file
        leaseExtender.TOTAL_BYTES_WRITTEN = Long.valueOf(0);
        while ((len = in.read(buf)) > 0) {
            tarFile.write(buf, 0, len);
            written = written + len;
            leaseExtender.TOTAL_BYTES_WRITTEN = written;
        }
        in.close();
    }

    @Override
    public void exportVM(OccpVM vm, String scenario, String exportName) throws VMOperationFailedException {
        boolean failure = false;
        HttpNfcLeaseExtender leaseExtender = null;
        ManagedObjectReference httpNfcLease = null;
        Thread t = null;
        VMOperationFailedException cause = null;
        try {
            File exportDir = new File(OccpAdmin.scenarioBaseDir + "/Export/");
            ManagedObjectReference vmmor = ((OccpEsxiVM) vm).mor;
            logger.finest("Getting the HTTP NFCLEASE for the VM: " + vm.getName());

            ManagedObjectReference ovfMgr = serviceContent.getOvfManager();
            httpNfcLease = vimPort.exportVm(vmmor);
            Object[] result = waitForValues(httpNfcLease, new String[] { "state" }, new String[] { "state" },
                    new Object[][] { new Object[] { HttpNfcLeaseState.READY, HttpNfcLeaseState.ERROR } });
            if (result[0].equals(HttpNfcLeaseState.READY)) {
                logger.finest("HttpNfcLeaseState: " + result[0]);
                HttpNfcLeaseInfo httpNfcLeaseInfo = (HttpNfcLeaseInfo) getEntityProp(httpNfcLease, "info");
                httpNfcLeaseInfo.setLeaseTimeout(300000000);
                List<HttpNfcLeaseManifestEntry> manifest = vimPort.httpNfcLeaseGetManifest(httpNfcLease);
                long diskCapacity = 0;
                for (HttpNfcLeaseManifestEntry entry : manifest) {
                    diskCapacity += entry.getSize();
                }
                logger.finest("Downloading " + diskCapacity + " bytes");
                leaseExtender = new HttpNfcLeaseExtender(httpNfcLease, vimPort, vm.getName(), diskCapacity, false);
                t = new Thread(leaseExtender);
                t.start();
                File ovaFile = new File(exportDir.toPath().resolve(exportName).toString());
                if (ovaFile.exists()) {
                    ovaFile.delete();
                }
                List<HttpNfcLeaseDeviceUrl> deviceUrlArr = httpNfcLeaseInfo.getDeviceUrl();

                // Fill in the file information
                OvfCreateDescriptorParams cdp = new OvfCreateDescriptorParams();
                cdp.setDescription("OCCP:" + scenario);
                cdp.setIncludeImageFiles(true);
                cdp.setName(((OccpEsxiVM) vm).name);
                for (HttpNfcLeaseDeviceUrl deviceUrl : deviceUrlArr) {
                    String deviceUrlStr = deviceUrl.getUrl();
                    String fileName = deviceUrlStr.substring(deviceUrlStr.lastIndexOf("/") + 1);
                    OvfFile imageFile = new OvfFile();
                    imageFile.setPath(fileName);
                    imageFile.setDeviceId(deviceUrl.getKey());
                    if (deviceUrl.getFileSize() != null) {
                        imageFile.setSize(deviceUrl.getFileSize());
                    }
                    cdp.getOvfFiles().add(imageFile);
                }
                OvfCreateDescriptorResult descriptor = vimPort.createDescriptor(ovfMgr, vmmor, cdp);
                List<LocalizedMethodFault> errors = descriptor.getError();
                for (LocalizedMethodFault error : errors) {
                    logger.severe(error.getLocalizedMessage());
                }
                for (LocalizedMethodFault warn : descriptor.getWarning()) {
                    logger.warning(warn.getLocalizedMessage());
                }
                if (!errors.isEmpty()) {
                    throw new IllegalArgumentException("Hypervisor refuses to export VM");
                }
                String ovfXML = descriptor.getOvfDescriptor();
                logger.finest(ovfXML);
                // Not to be confusing, remove the incorrect size; size is optional
                ovfXML = ovfXML.replaceAll("ovf:size=\"0\"", "");
                // Also, if a VirtualBox VM is imported into ESXi, some VirtualBox specific details are imported as well
                // We don't want to export those, because they won't make sense to the next hypervisor
                // Therefore, we remove evidence of this by removing the VirtualSystem/vbox:Machine tag
                if (ovfXML.contains("vbox:Machine")) {
                    try {
                        // First parse the XML from the server
                        DocumentBuilderFactory dBF = DocumentBuilderFactory.newInstance();
                        DocumentBuilder docBuilder;
                        docBuilder = dBF.newDocumentBuilder();
                        Document doc = docBuilder.parse(new InputSource(new StringReader(ovfXML)));
                        NodeList virtualSystem = doc.getElementsByTagName("VirtualSystem");
                        NodeList secondLevel = virtualSystem.item(0).getChildNodes();
                        // Remove the offending tag
                        for (int nodeNumber = 0; nodeNumber < secondLevel.getLength(); ++nodeNumber) {
                            Node candidate = secondLevel.item(nodeNumber);
                            if (candidate.getNodeName().equals("vbox:Machine")) {
                                virtualSystem.item(0).removeChild(candidate);
                            }
                        }
                        // Now re-generate the string version of the XML
                        StringWriter writer = new StringWriter();
                        Transformer transformer;
                        TransformerFactory factory = TransformerFactory.newInstance();
                        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                        // factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                        transformer = factory.newTransformer();
                        transformer.transform(new DOMSource(doc), new StreamResult(writer));
                        ovfXML = writer.toString();
                    } catch (TransformerFactoryConfigurationError | TransformerException | ParserConfigurationException
                            | SAXException e) {
                        logger.log(Level.SEVERE, "Unable to generate scenario XML for gameserver", e);
                        throw new VMOperationFailedException(this.name, vm.getName(), ErrorCode.EXPORT,
                                "Failed to modify OVF file", e);
                    }
                }

                // See the comment below for why this RandomAccessFile is necessary
                RandomAccessFile outputStream = new RandomAccessFile(ovaFile, "rw");
                TarArchiveOutputStream tarFile = new TarArchiveOutputStream(new FileOutputStream(outputStream.getFD()));
                tarFile.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                TarArchiveEntry ovfFile = new TarArchiveEntry(exportName.replace(".ova", ".ovf"));
                ovfFile.setSize(ovfXML.length());
                tarFile.putArchiveEntry(ovfFile);
                tarFile.write(ovfXML.getBytes());
                tarFile.closeArchiveEntry();

                // Fill in the rest of the OVA
                for (HttpNfcLeaseDeviceUrl deviceUrl : deviceUrlArr) {
                    String deviceUrlStr = deviceUrl.getUrl();
                    String fileName = deviceUrlStr.substring(deviceUrlStr.lastIndexOf("/") + 1);
                    logger.info("Downloading file name: " + fileName + " for the VM \"" + vm.getName()
                            + "\" on the hypervisor \"" + this.getName() + '"');
                    logger.finest("VMDK URL: " + deviceUrlStr.replace("*", host));
                    leaseExtender.currentFile = fileName;
                    TarArchiveEntry entry = new TarArchiveEntry(fileName);
                    long headerOffset = outputStream.getFilePointer();
                    long entrySize = diskCapacity;
                    if (entrySize == 0 && deviceUrl.getFileSize() != null) {
                        entrySize = deviceUrl.getFileSize();
                    }
                    if (entrySize == 0) {
                        entrySize = 077777777777L;
                    }
                    entry.setSize(entrySize);
                    tarFile.putArchiveEntry(entry);
                    writeVMDKFile(tarFile, deviceUrlStr.replace("*", host), leaseExtender);
                    long finishOffset = outputStream.getFilePointer();
                    /*
                     * Workaround an issue where we don't have sufficient information to create the Tar file.
                     * VMware can't give us the size of the VMDK file before we download it, since it compresses on the
                     * fly. On the other hand, TarArchiveOutputStream needs the file size before it can write the data.
                     * We work around this by providing a very large size, and then go back and patch up the header
                     * afterwards. To do this, we seek back to the entry header, rewrite it with the correct size, then
                     * seek back to where we were, write a header that has a zero file size. This makes the
                     * closeArchiveEntry complete without complaining. Note it is necessary to seek back to before the
                     * fake header before closing.
                     */
                    /* Hack part 1: rewrite the header with the correct information */
                    byte tarEntryHeader[] = new byte[TarConstants.DEFAULT_RCDSIZE];
                    entry.setSize(leaseExtender.TOTAL_BYTES_WRITTEN);
                    outputStream.seek(headerOffset);
                    entry.writeEntryHeader(tarEntryHeader);
                    outputStream.write(tarEntryHeader);

                    /* Hack part 2: convince the output stream we didn't need to write anything */
                    outputStream.seek(finishOffset);
                    entry.setSize(0);
                    tarFile.putArchiveEntry(entry);

                    /* Hack part 3: Go back and finish writing the archive entry */
                    outputStream.seek(finishOffset);
                    tarFile.closeArchiveEntry();

                    logger.info("Exported File " + fileName);
                }
                tarFile.finish();
                // Truncate the file
                outputStream.setLength(outputStream.getFilePointer());
                outputStream.close();
                logger.info("Completed Downloading the files for the VM \"" + vm.getName() + "\" on the hypervisor \""
                        + this.getName() + '"');

            } else {
                logger.severe("HttpNfcLeaseState not ready");
                for (Object o : result) {
                    logger.severe("HttpNfcLeaseState: " + o);
                }
                throw new IllegalArgumentException("Hypervisor refuses to allow uploads");
            }
        } catch (IllegalArgumentException | IOException | ConcurrentAccessFaultMsg | FileFaultFaultMsg
                | InvalidStateFaultMsg | RuntimeFaultFaultMsg | TaskInProgressFaultMsg | VmConfigFaultFaultMsg
                | InvalidPowerStateFaultMsg | TimedoutFaultMsg | InvalidPropertyFaultMsg e) {
            if (e.getClass() == SOAPFaultException.class) {
                printSoapFaultException((SOAPFaultException) e);
            }
            cause = new VMOperationFailedException(name, vm.getName(), ErrorCode.EXPORT, e);
            if (httpNfcLease != null) {
                LocalizedMethodFault fault = new LocalizedMethodFault();
                fault.setFault(new OvfImportFailed());
                try {
                    vimPort.httpNfcLeaseAbort(httpNfcLease, fault);
                } catch (InvalidStateFaultMsg | RuntimeFaultFaultMsg | TimedoutFaultMsg thrown) {
                    logger.log(Level.WARNING, "Failed to abort task; please cancel this task from VMware", thrown);
                    cause.addSuppressed(thrown);
                }
                failure = true;
            }
        } finally {
            // Shutdown the leaseExtender thread
            if (leaseExtender != null) {
                leaseExtender.vmdkFlag = true;
            }
            if (t != null) {
                t.interrupt();
            }
            try {
                if (!failure) {
                    vimPort.httpNfcLeaseProgress(httpNfcLease, 100);
                    vimPort.httpNfcLeaseComplete(httpNfcLease);
                }
            } catch (RuntimeFaultFaultMsg | TimedoutFaultMsg | InvalidStateFaultMsg | SOAPFaultException thrown) {
                logger.log(Level.SEVERE, "Failed to complete export task; please cancel this task from VMware", thrown);
                if (cause != null) {
                    cause.addSuppressed(thrown);
                } else {
                    cause = new VMOperationFailedException(name, vm.getName(), ErrorCode.EXPORT, thrown);
                }
            }
            if (cause != null) {
                throw cause;
            }
        }
    }

    @Override
    public void assignVMRam(OccpVM vm, int ram) throws VMOperationFailedException {
        ManagedObjectReference vmMor = ((OccpEsxiVM) vm).mor;

        VirtualMachineConfigInfo info;
        try {
            info = (VirtualMachineConfigInfo) getEntityProp(vmMor, "config");
            VirtualHardware hw = info.getHardware();
            int currentRam = hw.getMemoryMB();
            if (currentRam != ram) {
                VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
                spec.setMemoryMB(Long.valueOf(ram));
                ManagedObjectReference task = vimPort.reconfigVMTask(vmMor, spec);
                getTaskResultAfterDone(task);
            }
        } catch (InvalidPropertyFaultMsg | RuntimeFaultFaultMsg | ConcurrentAccessFaultMsg | DuplicateNameFaultMsg
                | FileFaultFaultMsg | InsufficientResourcesFaultFaultMsg | InvalidDatastoreFaultMsg
                | InvalidNameFaultMsg | InvalidStateFaultMsg | TaskInProgressFaultMsg | VmConfigFaultFaultMsg e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.ASSIGN_RAM, e);
        }
    }

    @Override
    public void deleteVM(OccpVM vm) throws VMOperationFailedException {
        ManagedObjectReference vmMor = ((OccpEsxiVM) vm).mor;
        try {
            ManagedObjectReference task = vimPort.destroyTask(vmMor);
            getTaskResultAfterDone(task);
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.DELETE_VM, e);
        }
    }

    @Override
    public void deleteSnapshot(OccpVM vm, String snapshotName) throws VMOperationFailedException {
        ManagedObjectReference snapmor;
        try {
            snapmor = getSnapshotReference(((OccpEsxiVM) vm).mor, snapshotName);
            if (snapmor != null) {
                ManagedObjectReference taskMor = vimPort.removeSnapshotTask(snapmor, false, true);
                getTaskResultAfterDone(taskMor);
            }
        } catch (Exception e) {
            throw new VMOperationFailedException(name, vm.getName(), ErrorCode.DELETE_SNAPSHOT, e);
        }
    }

    /**
     * The method returns the default devices from the HostSystem.
     * 
     * @param computeResMor
     *            A MoRef to the ComputeResource used by the HostSystem
     * @return Array of VirtualDevice containing the default devices for the
     *         HostSystem
     * @throws RuntimeFaultFaultMsg
     * @throws InvalidPropertyFaultMsg
     */
    private List<VirtualDevice> getDefaultDevices(ManagedObjectReference computeResMor)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        ManagedObjectReference envBrowseMor = (ManagedObjectReference) getEntityProp(computeResMor,
                "environmentBrowser");
        VirtualMachineConfigOption cfgOpt = vimPort.queryConfigOption(envBrowseMor, null, hostmor);
        List<VirtualDevice> defaultDevs = null;
        if (cfgOpt == null) {
            throw new RuntimeException("No VirtualHardwareInfo found in ComputeResource");
        }
        List<VirtualDevice> lvds = cfgOpt.getDefaultDevice();
        if (lvds == null) {
            throw new RuntimeException("No Datastore found in ComputeResource");
        }
        defaultDevs = lvds;
        return defaultDevs;
    }

    private String getVolumeName(String volName) {
        String volumeName = null;
        if (volName != null && volName.length() > 0) {
            volumeName = "[" + volName + "]";
        } else {
            volumeName = "[Local]";
        }

        return volumeName;
    }

    /**
     * Creates the vm config spec object.
     * 
     * @param vmName
     *            the vm name
     * @param computeResMor
     *            the compute res moref
     * @return the virtual machine config spec object
     * @throws RuntimeFaultFaultMsg
     * @throws InvalidPropertyFaultMsg
     */
    private VirtualMachineConfigSpec createVmConfigSpec(String vmName, ManagedObjectReference computeResMor)
            throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {

        ManagedObjectReference envBrowseMor = (ManagedObjectReference) getEntityProp(computeResMor,
                "environmentBrowser");
        ConfigTarget configTarget = vimPort.queryConfigTarget(envBrowseMor, hostmor);
        if (configTarget == null) {
            throw new RuntimeException("No ConfigTarget found in ComputeResource");
        }
        List<VirtualDevice> defaultDevices = getDefaultDevices(computeResMor);
        VirtualMachineConfigSpec configSpec = new VirtualMachineConfigSpec();
        String datastoreVolume = getVolumeName(this.datastore);
        VirtualMachineFileInfo vmfi = new VirtualMachineFileInfo();
        // TODO: Check with esxi
        vmfi.setVmPathName(datastoreVolume);
        configSpec.setFiles(vmfi);

        // Find the IDE controller
        VirtualDevice ideCtlr = null;
        for (int di = 0; di < defaultDevices.size(); di++) {
            if (defaultDevices.get(di) instanceof VirtualIDEController) {
                ideCtlr = defaultDevices.get(di);
                break;
            }
        }

        // Add a floppy
        VirtualDeviceConfigSpec floppySpec = new VirtualDeviceConfigSpec();
        floppySpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);
        VirtualFloppy floppy = new VirtualFloppy();
        VirtualFloppyRemoteDeviceBackingInfo flpBacking = new VirtualFloppyRemoteDeviceBackingInfo();
        flpBacking.setDeviceName("");
        flpBacking.setUseAutoDetect(Boolean.TRUE);
        floppy.setBacking(flpBacking);
        floppy.setKey(-1);
        floppySpec.setDevice(floppy);

        // Add a cdrom based on a physical device
        VirtualDeviceConfigSpec cdSpec = null;

        if (ideCtlr != null) {
            cdSpec = new VirtualDeviceConfigSpec();
            cdSpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);
            VirtualCdrom cdrom = new VirtualCdrom();
            // We can't set this yet, since we haven't uploaded it
            // and we can't upload it until we know the path of the VM
            // VirtualCdromIsoBackingInfo cdDeviceBacking = new VirtualCdromIsoBackingInfo();
            // cdDeviceBacking.setDatastore(dsMor);
            // cdDeviceBacking.setFileName(datastoreVolume + isoFilename);
            VirtualCdromRemoteAtapiBackingInfo cdDeviceBacking = new VirtualCdromRemoteAtapiBackingInfo();
            cdDeviceBacking.setDeviceName("");
            cdDeviceBacking.setUseAutoDetect(Boolean.TRUE);
            cdrom.setBacking(cdDeviceBacking);
            cdrom.setKey(-2);
            cdrom.setControllerKey(Integer.valueOf(ideCtlr.getKey()));
            cdrom.setUnitNumber(Integer.valueOf(0));
            cdSpec.setDevice(cdrom);
        }

        // Add public network (in case this is the vpn)
        VirtualDeviceConfigSpec nic = new VirtualDeviceConfigSpec();
        VirtualEthernetCard card = new VirtualVmxnet3();
        VirtualDeviceConnectInfo connect = new VirtualDeviceConnectInfo();
        VirtualEthernetCardNetworkBackingInfo nicBacking;
        nic.setOperation(VirtualDeviceConfigSpecOperation.ADD);
        card.setMacAddress(null);
        card.setAddressType("Generated");
        connect.setStartConnected(true);
        card.setConnectable(connect);
        nicBacking = new VirtualEthernetCardNetworkBackingInfo();
        nicBacking.setDeviceName(this.publicnet);
        card.setBacking(nicBacking);
        nic.setDevice(card);

        List<VirtualDeviceConfigSpec> deviceConfigSpec = new ArrayList<VirtualDeviceConfigSpec>();
        deviceConfigSpec.add(floppySpec);
        deviceConfigSpec.add(cdSpec);
        deviceConfigSpec.add(nic);
        configSpec.getDeviceChange().addAll(deviceConfigSpec);
        return configSpec;
    }

    @Override
    public OccpVM createVMwithISO(String vmName, String isoFilename) throws OccpException {
        Exception thrown = null;
        try {
            ManagedObjectReference crMor = (ManagedObjectReference) getEntityProp(hostmor, "parent");
            ManagedObjectReference poolMor = (ManagedObjectReference) getEntityProp(crMor, "resourcePool");
            VirtualMachineConfigSpec vmConfigSpec = createVmConfigSpec(vmName, crMor);
            vmConfigSpec.setName(vmName);
            vmConfigSpec.setAnnotation("occp");
            vmConfigSpec.setMemoryMB(Long.valueOf(96));
            vmConfigSpec.setNumCPUs(1);
            vmConfigSpec.setGuestId("otherLinuxGuest");
            OptionValue ov = new OptionValue();
            ov.setKey("occp.group");
            ov.setValue(groupName);
            vmConfigSpec.getExtraConfig().add(ov);
            ManagedObjectReference taskmor = vimPort.createVMTask(folderRef, vmConfigSpec, poolMor, hostmor);
            getTaskResultAfterDone(taskmor);
            logger.fine("Success: Creating VM  - [ " + vmName + " ]");
            ManagedObjectReference vmMor = (ManagedObjectReference) getEntityProp(taskmor, "info.result");
            // Floppy, ISO, what's the difference?
            uploadFloppy(vmMor, isoFilename, false);
            attachISO(vmMor, isoFilename);
            OccpEsxiVM newVM = new OccpEsxiVM();
            newVM.mor = vmMor;
            newVM.name = vmName;
            assignVMGroup(vmName, vmMor);
            return newVM;
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Task failed", e);
            thrown = e;
        } catch (RuntimeFaultFaultMsg e) {
            logger.log(Level.SEVERE, "Runtime Error", e);
            thrown = e;
        } catch (IOException e) {
            thrown = e;
        } catch (AlreadyExistsFaultMsg | OutOfBoundsFaultMsg e) {
            logger.log(Level.SEVERE, "Logic Error", e);
            thrown = e;
        } catch (InvalidPropertyFaultMsg | ConcurrentAccessFaultMsg | DuplicateNameFaultMsg | FileFaultFaultMsg
                | InsufficientResourcesFaultFaultMsg | InvalidDatastoreFaultMsg | InvalidNameFaultMsg
                | InvalidStateFaultMsg | TaskInProgressFaultMsg | VmConfigFaultFaultMsg e) {
            logger.log(Level.SEVERE, "Configuration Error", e);
            thrown = e;
        }
        throw new VMOperationFailedException(name, vmName, ErrorCode.CREATE_VM, thrown);
    }

    /**
     * Returns the usage message for this hypervisor's accepted parameters
     * 
     * @return usage of this hypervisor's accepted parameters
     */
    public static String getUsage() {
        StringBuilder usage = new StringBuilder();
        usage.append("esxi | vcenter:");
        usage.append("\n\t--datacenter <datacenter> - The name of the datacenter. Only required for vcenter");
        usage.append("\n\t--datastore <datacenter> - The name of the datastore");
        usage.append("\n\t--folder <path> - The path of a folder used for OCCP VMs");
        usage.append("\n\t--host <host> [required] - The name of the host to use");
        usage.append("\n\t--password <password> [optional] The password used to authenticate with the VMware API, blank passwords are specified as \"\"");
        usage.append("\n\t--publicnet <name of network> [requried] The name of a network that can access the internet");
        usage.append("\n\t--url <URL> [requried] The URL to connect to VMware API");
        usage.append("\n\t--username <username> [required] The username used to authenticate with the VMware API");

        return usage.toString();
    }
}
