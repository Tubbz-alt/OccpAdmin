/**
 * {@code SetupNetwork} handles creating the VPN configuration during setup
 * 
 */
package edu.uri.dfcsc.occp;

import java.io.*;
import java.lang.ProcessBuilder.Redirect;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.lang.StringUtils;

import edu.uri.dfcsc.occp.OccpAdmin.VpnConnection;
import edu.uri.dfcsc.occp.OccpHV.OccpVM;
import edu.uri.dfcsc.occp.exceptions.OccpException;
import edu.uri.dfcsc.occp.exceptions.vm.HVOperationFailedException;
import edu.uri.dfcsc.occp.exceptions.vm.VMNotFoundException;
import edu.uri.dfcsc.occp.exceptions.vm.VMOperationFailedException;
import edu.uri.dfcsc.occp.exceptions.vm.VMOperationFailedException.ErrorCode;

/**
 * @author Kevin Bryan (bryank@cs.uri.edu)
 */
public class SetupNetwork {
    private static Logger logger = Logger.getLogger(SetupNetwork.class.getName());
    private boolean isReady = false, haveTried = false;
    private Process openvpn = null;
    private Thread watchThread = null;
    private final Map<String, OccpVpnVm> vpnvms = new HashMap<>();
    private final Map<String, OccpHV> hvs;
    private final String setupNetworkName = OccpAdmin.setupNetworkName;
    private final String setupIface = OccpAdmin.setupinterface;
    private final String vpnIfaceName = OccpAdmin.globalConfig.getProperty("localVPNInterface", "eth0");

    /**
     * @param hvs - Map of Hypervisor names to Hypervisor objects
     */
    public SetupNetwork(final Map<String, OccpHV> hvs) {
        this.hvs = hvs;
    }

    /**
     * Create configuration files for each of the hypervisors's VPNs, as well as the admin VM's
     * 
     * @return success
     */
    public boolean createConfiguration() {
        /* Create VPN configuration for setup-phase */
        CA setupCa = new CA();
        CA.CertPair setupCaKey = setupCa.generateCA();
        Map<String, VpnConnection> net2vpn = new HashMap<>();
        NetworkInterface vpnIface;
        try {
            vpnIface = NetworkInterface.getByName(vpnIfaceName);
        } catch (SocketException e) {
            logger.log(Level.SEVERE, "Could not determine local address for the VPN using interface " + vpnIfaceName, e);
            return false;
        }
        Enumeration<InetAddress> ee = vpnIface.getInetAddresses();
        String vpnip = null;
        while (ee.hasMoreElements()) {
            InetAddress i = ee.nextElement();
            // For now only support IPv4
            byte[] byteAddress = i.getAddress();
            if (byteAddress.length == 4) {
                vpnip = i.getHostAddress();
                logger.info("Determined local IP for setup VPN to be: " + vpnip);
            }
        }
        final int vpnPort = 7890;
        VpnConnection vpn = new VpnConnection(vpnip, vpnPort, setupCa, setupCaKey);
        if (!OccpAdmin.createSetupVPNFiles("AdminVmVpn", vpnip, vpn)) {
            return false;
        }
        int lastOctet = 2;
        for (String aHvName : hvs.keySet()) {
            OccpHV hv = hvs.get(aHvName);
            // No need to do this setup for a local HV
            if (hv.getLocal()) {
                continue;
            }
            net2vpn.put(setupNetworkName, new VpnConnection(vpnip, vpnPort, setupCa, setupCaKey));
            OccpVM vm;
            try {
                vm = hv.getBaseVM(OccpParser.SETUPVPN_NAME);
            } catch (VMNotFoundException e) {
                logger.severe("Could not find \"" + OccpParser.SETUPVPN_NAME + "\" on " + aHvName);
                return false;
            } catch (HVOperationFailedException e) {
                logger.severe(e.getMessage());
                return false;
            }
            String ip = "12.14.17." + lastOctet;
            ++lastOctet;
            vpnvms.put(aHvName, new OccpVpnVm(hv, vm, ip));
            if (!OccpAdmin.createSetupVPNFiles(aHvName, null, vpn)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param hvName Name of the hypervisor to connect to
     * @return Success if all of the VPN is ready
     */
    public synchronized boolean connect(String hvName) {
        if (haveTried && !isReady) {
            return false;
        }

        // No need to do this setup for a local HV
        if (hvs.get(hvName).getLocal()) {
            return true;
        }
        // Sanity check that Setup VMs are installed, configured
        boolean failure = false;
        OccpVpnVm vpnvm = this.vpnvms.get(hvName);
        if (vpnvm.isConnected()) {
            return true;
        }
        logger.info("Attempting to start the \"" + OccpParser.SETUPVPN_NAME + "\" on the hypervisor \"" + hvName + '"');
        try {
            /* null network means don't touch it */
            vpnvm.verify(Arrays.asList(new String[] { null, setupNetworkName }), "importdir");
            // Start the VMs
            vpnvm.powerOnAndWait();

            // Send Configuration files
            String sourceBase = OccpAdmin.occpHiddenDirPath.resolve(hvName).toString() + "/";
            String sourcePath = sourceBase + setupNetworkName + ".conf";
            String destPath = "/etc/openvpn/" + setupNetworkName + ".conf";
            vpnvm.transferFileToVM(sourcePath, destPath, false);
            sourcePath = sourceBase + "up.sh";
            vpnvm.transferFileToVM(sourcePath, "/etc/openvpn/up.sh", true);

            // Start openvpn
            vpnvm.startVPN();
        } catch (OccpException e) {
            logger.log(Level.SEVERE, "Failed to start/configure " + OccpParser.SETUPVPN_NAME, e);
            this.stop();
        }

        logger.info("Started the \"" + OccpParser.SETUPVPN_NAME + "\" on the hypervisor \"" + hvName
                + "\" successfully");
        return !failure;
    }

    /**
     * Set up the networking on this machine and start OpenVPN
     * 
     * @return success or failure
     */
    public synchronized Boolean setup() {
        String[][] cfgcmds = { { "sudo", "brctl", "addbr", "br0" }, { "sudo", "brctl", "addif", "br0", setupIface },
                { "sudo", "brctl", "stp", "br0", "on" }, { "sudo", "brctl", "setbridgeprio", "br0", "0" },
                { "sudo", "ifconfig", "br0", "12.14.16.1", "netmask", "255.255.0.0", "up" },
                { "sudo", "ifconfig", setupIface, "up", "promisc" } };
        Runtime rt = Runtime.getRuntime();
        for (String[] cfgcmd : cfgcmds) {
            try {
                Process p = rt.exec(cfgcmd);
                int result = p.waitFor();
                if (result != 0) {
                    InputStream stderr = p.getErrorStream();
                    String errmsg = org.apache.commons.io.IOUtils.toString(stderr);
                    if ((cfgcmd[2].equals("addbr") && !errmsg.contains("already exists"))
                            || (cfgcmd[2].equals("addif") && !errmsg.contains("already a member"))) {
                        String cmd = StringUtils.join(cfgcmd, " ");
                        logger.severe("Error setting up bridge/vpn interface: " + cmd + ": " + errmsg);
                        return false;
                    }
                }
            } catch (IOException | InterruptedException e) {
                logger.log(Level.SEVERE, "Error setting up bridge/vpn interface: " + StringUtils.join(cfgcmd, " "), e);
                return false;
            }
        }
        /* Important: br0 must not be it's own argument */
        String[] openvpncmd = { "sudo", "openvpn", "--config",
                OccpAdmin.occpHiddenDirPath.resolve("AdminVmVpn/" + setupNetworkName + ".conf").toString() };
        ProcessBuilder pb = new ProcessBuilder(Arrays.asList(openvpncmd));
        StringBuilder outputBuffer = new StringBuilder();
        BufferedReader reader = null;
        try {
            pb.redirectInput(Redirect.INHERIT).redirectOutput(Redirect.PIPE).redirectError(Redirect.INHERIT);
            // Ensure it isn't running already; mostly for debug purposes
            this.stop();
            haveTried = true;
            openvpn = pb.start();
            InputStream stdin = openvpn.getInputStream();
            reader = new BufferedReader(new InputStreamReader(stdin));
            String line = null;
            while (!isReady) {
                line = reader.readLine();
                if (line == null) {
                    break;
                }
                outputBuffer.append(line + "\n");
                logger.finest(line);
                if (line.contains("Initialization Sequence Completed")) {
                    isReady = true;
                    watchThread = new Thread(new WatchOpenVPN());
                    watchThread.start();
                }
            }
            if (line == null) {
                logger.warning("Failure starting openvpn:" + outputBuffer);
            }
        } catch (IOException e) {
            isReady = false;
            logger.log(Level.WARNING, "Failure starting openvpn", e);
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ioe) {
                    logger.warning("Failed to close handle to openvpn");
                }
            }
            if (openvpn != null) {
                openvpn.destroy();
            }
            return false;
        } finally {
        }
        return isReady;
    }

    /* Monitor the openvpn sub-process */
    private class WatchOpenVPN implements Runnable {
        @Override
        public void run() {
            Thread.currentThread().setName("OpenVPN Watcher");
            BufferedReader reader = null;
            try {
                InputStream stdin = openvpn.getInputStream();
                reader = new BufferedReader(new InputStreamReader(stdin));
                String line;
                do {
                    line = reader.readLine();
                    if (line != null) {
                        logger.fine(line);
                    }
                } while (line != null);
                openvpn.waitFor();
            } catch (InterruptedException | IOException e) {
                isReady = false;
                // Old Java API doesn't seem to give better mechanism for determining cause
                if (!e.getMessage().contains("Stream closed")) {
                    logger.log(Level.WARNING, "Failure running openvpn", e);
                }
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        logger.warning("Failed to close handle to openvpn");
                    }
                }
                isReady = false;
                if (openvpn != null) {
                    openvpn.destroy();
                }
            }
        }
    }

    /**
     * Force a shutdown of the service
     */
    public void stop() {
        try {
            if (openvpn != null && isReady) {
                openvpn.destroy();
            }
            // This ensures that it is shutdown from a previous run
            // Stop any existing openvpn process
            Runtime rt = Runtime.getRuntime();
            String[] cmd = { "sudo", "pkill", "-TERM", "openvpn" };
            rt.exec(cmd).waitFor();
            logger.finest("Doing hard shutdown of openvpn");
            synchronized (this) {
                if (watchThread != null) {
                    watchThread.join();
                }
            }

            // Stop the VMs
            for (Entry<String, OccpVpnVm> entry : vpnvms.entrySet()) {
                logger.fine("Doing shutdown of VPN VM on " + entry.getKey());
                try {
                    entry.getValue().powerOff();
                } catch (OccpException e) {
                    logger.log(Level.WARNING, "Failed to shutdown VM", e);
                }
            }
        } catch (InterruptedException e) {

        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to stop openvpn", e);
        } finally {
            isReady = false;
        }
    }

    /**
     * Transfer a file to a hypervisor that requires local files for import.
     * It should use the importdir as the destination, which should be setup as a shared folder.
     * It will start the VPN machines if they aren't started by calling connect() for you.
     * 
     * @param hv Hypervisor to transfer the file to
     * @param from Local file name
     * @throws OccpException
     */
    public void stageFile(String hv, String from) throws OccpException {
        OccpHV occphv = hvs.get(hv);
        if (occphv.getLocal()) {
            logger.info("Copying " + from);
            if (occphv.getClass() == OccpVBoxHV.class) {
                Path filename = FileSystems.getDefault().getPath(from).getFileName();
                Path to = OccpAdmin.scenarioBaseDir.resolve(filename);
                logger.info("Copying " + from + " to " + to.toString());
                File toFile = to.toFile();
                if (toFile.exists()) {
                    return;
                }
                try (FileInputStream fromIS = new FileInputStream(from);
                        FileOutputStream toIS = new FileOutputStream(toFile)) {
                    IOUtils.copy(fromIS, toIS);
                } catch (Exception e) {
                    throw new HVOperationFailedException(hv, "Failed to copy file", e).set("from", from).set("to", to);
                }
            }
            return;
        }
        OccpVpnVm vpn = this.vpnvms.get(hv);
        if (vpn != null) {
            // We can't stage the file if we aren't connected. connect() guards against second calls
            if (this.connect(hv)) {
                vpnvms.get(hv).stageFile(from);
            }
        } else {
            throw new VMOperationFailedException(hv, OccpParser.SETUPVPN_NAME, ErrorCode.TRANSFER_TO,
                    "Unexpected error");
        }
    }

    /**
     * Transfer a file to from hypervisor that requires local files for export.
     * It should use the importdir as the base location, which should be setup as a shared folder.
     * It will start the VPN machines if they aren't started by calling connect() for you.
     * 
     * @param hv Hypervisor to transfer the file to
     * @param from Remote file name (relative to importdir)
     * @param to Local file name
     * @throws OccpException
     */
    public void fetchFile(String hv, String from, String to) throws OccpException {
        if (hvs.get(hv).getLocal()) {
            return;
        }
        OccpVpnVm vpn = this.vpnvms.get(hv);
        if (vpn != null) {
            // We can't stage the file if we aren't connected. connect() guards against second calls
            if (this.connect(hv)) {
                vpnvms.get(hv).fetchFile(from, to);
            }
        }
    }
}
