package edu.uri.dfcsc.occp;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.net.util.SubnetUtils;
import org.bouncycastle.operator.OperatorCreationException;

import de.waldheinz.fs.FileSystem;
import de.waldheinz.fs.FsDirectoryEntry;
import de.waldheinz.fs.FsFile;
import de.waldheinz.fs.fat.SuperFloppyFormatter;
import de.waldheinz.fs.util.FileDisk;

import edu.uri.dfcsc.occp.CA.CertPair;
import edu.uri.dfcsc.occp.OccpHV.OccpVM;
import edu.uri.dfcsc.occp.OccpNetworkInterface.InterfaceType;
import edu.uri.dfcsc.occp.exceptions.OccpException;
import edu.uri.dfcsc.occp.exceptions.configmanager.ConfigManagerException;
import edu.uri.dfcsc.occp.exceptions.configmanager.ConfigManagerTemporaryFailureException;
import edu.uri.dfcsc.occp.exceptions.vm.VMNotFoundException;
import edu.uri.dfcsc.occp.exceptions.vm.VMOperationFailedException;
import edu.uri.dfcsc.occp.utils.DHCPServer;

/**
 * <pre>
 * OccpAdmin
 * 
 *  This utility will connect to the Hypervisor to ensure necessary
 *  VMs are available, optionally create new snapshots, and then start
 * the VMs.
 * </pre>
 */

public class OccpAdmin {
    private static final String majorVersion = "1", minorVersion = "0", patchVersion = "0";
    /**
     * Location of internal use items (vpn.iso, router.iso, hypervisors.xml, puppet stuff)
     */
    public static Path occpHiddenDirPath;
    private static long startTime = System.currentTimeMillis();
    private static String hypervisor;
    private static String hvName = "specified";
    private static final String[] Modes = new String[] { "addhv", "cleanup", "delhv", "deploy", "export", "launch",
            "poweroff", "verify" };
    private static String modeList;
    private static String ConfigFile;
    /**
     * The directory the scenario config file is located
     */
    public static Path scenarioBaseDir;
    /**
     * The name of the scenario, as inferred by the scenario config's file name minus extension
     */
    public static String scenarioName;
    public static String instanceId = null;
    private static String runMode;
    private static String hvMap;
    private static Map<String, List<OccpHost>> hv2vm;
    private static Map<String, String> vm2hv;
    private static Map<String, Set<OccpNetwork>> hv2net;
    private static Map<String, Set<String>> net2hv;
    private static boolean failure;
    private static final Logger logger = Logger.getLogger(OccpAdmin.class.getPackage().getName());
    private static Map<String, OccpHV> hvs;
    private static Map<String, String> hv2vpnip;
    private static Map<String, String> hv2vpngw;
    private static Map<String, Set<String>> hv2net_vpn;
    private static Map<String, VpnConnection> net2vpn;
    private static int clientNum = 0;
    private static int serverNum = 0;

    private static ThreadPoolExecutor exec;
    // used by DHCP code to prevent duplicate assignment
    private static int nextOctet = 2;
    private static DHCPServer mondhcp = new DHCPServer();
    private static SetupNetwork setup = null;

    /**
     * Properties from occp.conf
     */
    public static Properties globalConfig;
    private static OccpParser parser;

    /**
     * The name of the network used during setup phase.
     * It can be set using occp.conf's setupNetworkName property
     */
    public static String setupNetworkName;
    /**
     * The interface connected to the setup network
     */
    public static String setupinterface;
    private static boolean localFlag = true;
    private static boolean regenFlag = false;

    /**
     * A simple container for holding information about a VpnConnection
     */
    private static ConfigManagerControl configManager = null;
    static final String RTR_GS_LINK = "rtr-gs-link";

    /**
     * Enum for the possible Exit Codes for this program
     */
    public static enum ExitCode {
        // @formatter:off
        /**
         * Everything went fine, no known failures
         */
        OK(0),
        /**
         * Problem with the config file for the admin program itself
         */
        ADMIN_CONFIG(1),
        /**
         * Missing the required scenario config file
         */
        SCENARIO_CONFIG_MISSING(2),
        /**
         * Trouble parsing the scenario config file
         */
        SCENARIO_CONFIG_PARSE(3),
        /**
         * Trouble caching the hypervisor
         */
        HYPERVISOR_CACHE(4),
        /**
         * Trouble with the hypervisor map file
         */
        HYPERVISOR_MAP(5),
        /**
         * Trouble connecting to a hypervisor
         */
        HYPERVISOR_CONNECT(6),
        /**
         * Trouble exporting
         */
        EXPORT_FAILURE(7),
        /**
         * Trouble creating the router floppy
         */
        ROUTER_FLOPPY(8),
        /**
         * Problem setting up the Runtime VPN
         */
        RUNTIME_VPN(9),
        /**
         * Problem setting up the Runtime VPN
         */
        ILLEGAL_ARGUMENT(10),
        /**
         * Problem writing the reports
         */
        REPORT_WRITE_FAILURE(11),
        /**
         * Something bad happened but we aren't sure what, hopefully this catch all is not ever needed
         */
        GENERAL_FAILURE(99);
        // @formatter:on
        private final int value;

        ExitCode(int code) {
            this.value = code;
        }
    }

    /**
     * Container class for connection properties
     */
    public static final class VpnConnection {
        String ip;
        int port;
        CA.CertPair cp;
        CA ca;

        VpnConnection(String i, int p, CA ca0, CertPair caKey) {
            ip = i;
            port = p;
            ca = ca0;
            cp = caKey;
        }
    }

    private static void printUsage() {
        String runProgramName = "OccpAdmin";
        System.out.println("OccpAdmin - OCCP Administration utility");
        System.out.println("Visit https://www.opencyberchallenge.net for more detailed information");
        System.out.println("\nGlobal Parameters:");
        System.out.println("--config <path to file> - The path to the scenario file you wish to use");
        System.out.println("--instanceid <id> - Specifies the instance id of this scenario");
        System.out.println("--hvmap <path to map file> - The path to the hypervisor map file");
        System.out
                .println("--hvname <name> - Specifies the name of the hypervisor to be cached, used, or removed depending on the mode.");
        System.out.println("--hvtype <type> - Specifies the vendor/type of hypervisor");
        System.out.println("--mode <mode> - The operation mode for this program. Mode must be one of: " + modeList);
        System.out
                .println("\taddhv - Adds a hypervisor to the hypervisor cache. Requires --hvname and --hvtype as well as any hypervisor specific options or the \"remote\" flag");
        System.out.println("\tcleanup - (unimplemented) Will remove a scenario");
        System.out.println("\tdelhv - Removes a cached hypervisor by --hvname");
        System.out.println("\tdeploy - Prepares a scenario for launch but does not turn on the VMs");
        System.out.println("\texport - Packages a scenario for distribution to OCCP users");
        System.out.println("\tlaunch - Prepares a scenario for launch and powers on the VMs");
        System.out.println("\tpoweroff - Power off all the VMs in a scenario");
        System.out.println("\tverify - Check what would need to be done for a deploy or launch but do not act");
        System.out.println("--regen - Causes a regeneration of the VSN for deploy and launch modes");
        System.out.println("--remote - Specfies that hypervisor is not the same one that the AdminVM is running on");
        System.out.println("--version - Displays the version for this program and the Admin VM");
        System.out.println("\nHypervisor Specific Options:");
        System.out.println(OccpVBoxHV.getUsage());
        System.out.println(OccpEsxiHV.getUsage());
        System.out.println("\nExamples:");
        System.out.println("Adding a VirtualBox hypervisor then using it to deploy");
        System.out
                .println(runProgramName
                        + " --mode addhv --hvname myOCCPHV --hvtype vbox --url http://my-ip-address:18083 --username vboxuser --password vboxpass --importdir /path/on/host/to/shared/folder");
        System.out.println(runProgramName
                + " --mode deploy --hvname myOCCPHV --config /path/on/adminvm/to/share/scenario/scenarioFile.xml");
        System.out.println("Note: You do not have to add the hypervisor, you could specify the options each time");

        System.out.println("\nRegenerating a scenario");
        System.out
                .println(runProgramName
                        + " --mode deploy --hvname myOCCPHV --config /path/on/adminvm/to/share/scenario/scenarioFile.xml --regen");

        System.out.println("\nLaunching and Powering off a scenario");
        System.out.println(runProgramName
                + " --mode launch --hvname myOCCPHV --config /path/on/adminvm/to/share/scenario/scenarioFile.xml");
        System.out.println(runProgramName
                + " --mode poweroff --hvname myOCCPHV --config /path/on/adminvm/to/share/scenario/scenarioFile.xml");
    }

    /**
     * Gets this program's version
     * 
     * @return version string
     */
    public static String getProgramVersion() {
        return majorVersion + "." + minorVersion + "." + patchVersion;
    }

    /**
     * Gets the AdminVM version if possible
     * 
     * @return the AdminVM version string or UNKNOWN if unable to determine
     */
    public static String getAdminVMVersion() {
        String version = "UNKNOWN";
        // Try and read the version file
        File versionFile = new File("/etc/occp-vm-release");
        try {
            FileReader fileReader = new FileReader(versionFile);
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            version = bufferedReader.readLine();
            bufferedReader.close();
        } catch (FileNotFoundException e) {
            logger.warning("Unable to find the version file for the Admin VM.");
        } catch (IOException e) {
            logger.warning("Unable to read the version file for the Admin VM.");
        }
        return version;
    }

    /**
     * Prints the version for the Admin program and VM
     */
    public static void printVersion() {
        System.out.println("Admin Program: " + getProgramVersion());
        System.out.println("Admin VM: " + getAdminVMVersion());
    }

    /**
     * Sets the Regen flag to the given value
     * 
     * @param regen - the value to set
     */
    public static void setRegenFlag(final boolean regen) {
        regenFlag = regen;
    }

    /**
     * Gets the Regen Flag's value
     * 
     * @return - regen flag's value
     */
    public static boolean getRegenFlag() {
        return regenFlag;
    }

    private static String[] parseParameters(String[] args) throws IllegalArgumentException {
        int ai = 0;
        String param = "";
        String val = "";
        ArrayList<String> unknownOptions = new ArrayList<>();
        while (ai < args.length) {
            param = args[ai].trim();
            if (ai + 1 < args.length) {
                val = args[ai + 1].trim();
            }
            if (param.equalsIgnoreCase("--help")) {
                printUsage();
                exitProgram(ExitCode.OK.value);
            } else if (param.equalsIgnoreCase("--version")) {
                printVersion();
                exitProgram(ExitCode.OK.value);
            } else if (param.equalsIgnoreCase("--config") && !val.startsWith("--") && !val.isEmpty()) {
                ConfigFile = val;
            } else if (param.equalsIgnoreCase("--mode") && !val.startsWith("--") && !val.isEmpty()) {
                runMode = val;
            } else if (param.equalsIgnoreCase("--hvtype") && !val.startsWith("--") && !val.isEmpty()) {
                hypervisor = val;
                // Hypervisors may need to process hvtype
                unknownOptions.add("--hvtype");
                unknownOptions.add(val);
            } else if (param.equalsIgnoreCase("--hvname") && !val.startsWith("--") && !val.isEmpty()) {
                hvName = val;
            } else if (param.equalsIgnoreCase("--hvmap") && !val.startsWith("--") && !val.isEmpty()) {
                hvMap = val;
            } else if (param.equalsIgnoreCase("--instanceid") && !val.startsWith("--") && !val.isEmpty()) {
                instanceId = val;
            } else if (param.equalsIgnoreCase("--remote")) {
                localFlag = false;
                --ai; // No value
            } else if (param.equalsIgnoreCase("--regen")) {
                setRegenFlag(true);
                --ai; // No value
            } else {
                unknownOptions.add(param);
                --ai;
            }

            val = "";
            ai += 2;
        }
        if (runMode == null || !Arrays.asList(Modes).contains(runMode)) {
            throw new IllegalArgumentException("Expected --mode " + modeList);
        }
        if (runMode.equalsIgnoreCase("addhv") && ((hvName.equals("specified") || hypervisor == null))) {
            throw new IllegalArgumentException("addhv requires --hvname and --hvtype");
        }
        if (runMode.equalsIgnoreCase("delhv") && hvName.equals("specified")) {
            throw new IllegalArgumentException("delhv requires --hvname");
        }
        if (hvName.equals("specified") && hypervisor == null && hvMap == null) {
            throw new IllegalArgumentException("No hypervisor specified");
        }
        return unknownOptions.toArray(new String[unknownOptions.size()]);
    }

    /**
     * Bypasses Cert check Used here because we assume you aren't running this on a production environment
     * 
     * @author VMware
     */
    private static class TrustAllTrustManager implements javax.net.ssl.TrustManager, javax.net.ssl.X509TrustManager {

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return null;
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType)
                throws java.security.cert.CertificateException {
            for (int idx = 0; idx < certs.length; ++idx) {
                logger.finest("certificate(" + idx + "): " + certs[idx].getSubjectX500Principal().toString());
            }
            return;
        }

        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType)
                throws java.security.cert.CertificateException {
            return;
        }
    }

    private static void trustAllHttpsCertificates() throws NoSuchAlgorithmException, KeyManagementException {
        // Create a trust manager that does not validate certificate chains:
        javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[1];
        javax.net.ssl.TrustManager tm = new TrustAllTrustManager();
        trustAllCerts[0] = tm;
        javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("SSL");
        javax.net.ssl.SSLSessionContext sslsc = sc.getServerSessionContext();
        sslsc.setSessionTimeout(0);
        sc.init(null, trustAllCerts, null);
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    }

    private static FileSystem mountFloppy(String fileName) {
        try {
            FileDisk disk;
            disk = FileDisk.create(new File(fileName), (long) 1440 * 1024);
            return SuperFloppyFormatter.get(disk).format();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to mount/format " + fileName + ".img:", e);
            exitProgram(ExitCode.ROUTER_FLOPPY.value);
            return null;
        }
    }

    private static PrintStream createFloppyFile(FileSystem fs, String name) throws IOException {
        FsDirectoryEntry floppyEntry = fs.getRoot().addFile(name);
        FsFile floppyfile = floppyEntry.getFile();
        return new PrintStream(new FsFileAdapter(floppyfile));
    }

    private static void unmountFloppy(FileSystem fs) {
        try {
            fs.close();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to unmount floppy", e);
        }
    }

    /**
     * Queries the user for a password of a named resource (used by the hypervisors)
     * 
     * @param name Item asking for a password
     * @return the password
     */
    public static char[] getPassword(String name) {
        Console cons;
        if ((cons = System.console()) != null) {
            return cons.readPassword("Please provide password for %s: ", name);
        }
        return new char[] { 0 };
    }

    private static boolean checkNetworksOnHV(OccpHV hv, Collection<OccpNetwork> collection) {
        boolean Failure = false;
        for (OccpNetwork net : collection) {
            if (!hv.networkExists(net.getLabel())) {
                if (!runMode.equals("verify")) {
                    logger.fine("Creating network " + net.getLabel());
                    try {
                        hv.createNetwork(net.getLabel());
                    } catch (OccpException e) {
                        logger.log(Level.SEVERE, e.getMessage(), e);
                        Failure = true;
                    }
                } else {
                    logger.info("Network " + net.getLabel() + " not found (Running deploy will fix this)");
                }
            }
        }
        return !Failure;
    }

    // Determine which phase VM we have available for this host
    // Phase 0 means deploy from base VM (clone, phase1, clone, phase2)
    // Phase 1 means only do phase 2 deploy (clone, phase2)
    // Phase 2 means it's all done
    private static int findVMPhase(OccpHV hv, OccpHost host) throws OccpException {
        if (host.getLabel().equals(OccpParser.ROUTER_NAME)) {
            return 2;
        }
        try {
            OccpVM vm = hv.getVM(host.getLabel());
            if (hv.hasSnapshot(vm, "phase2")) {
                if (!runMode.equals("verify")) {
                    hv.revertToSnapshot(vm, "phase2");
                }
                return 2;
            }
            if (hv.hasSnapshot(vm, "phase1")) {
                if (!runMode.equals("verify")) {
                    hv.revertToSnapshot(vm, "phase1");
                }
                return 1;
            }
            return -1;
        } catch (VMNotFoundException e) {
            String isoFile = host.getIsoName();
            if (isoFile != null && scenarioBaseDir.resolve(isoFile).toFile().exists()) {
                return 2;
            }
            String importVM = host.getOvaName();
            if (importVM != null && scenarioBaseDir.resolve(importVM).toFile().exists()) {
                return host.getPhase();
            }
            if (host.getClone() != null) {
                return host.getPhase();
            }

            if (host.getBaseVM() != null) {
                return 0;
            }
        }
        return -1;
    }

    private static boolean ensureDHCPRunning() throws OccpException {
        if (!runMode.equals("verify")) {
            if (!mondhcp.isRunning()) {
                // Ensure the configuration files exist, even if they aren't complete
                createSetupDHCP();
                if (!mondhcp.setup()) {
                    logger.severe("DHCP Server did not start");
                    return false;
                }
                return true;
            }
        }
        return true;
    }

    /**
     * If a ConfigManagerControl object has not been setup successfully yet, this will try and do so.
     * 
     * @throws ConfigManagerException
     */
    private synchronized static void setupConfigManager() throws ConfigManagerException {

        if (configManager == null) {
            // One was not previously setup or had errors in the setup

            // Currently only supporting puppet, however if a more appropriate solution comes along, we hope it can be
            // dropped in fairly easily
            configManager = new PuppetControl(parser.getOccpHosts());
            try {
                configManager.setup(scenarioBaseDir.toString());
                logger.info("A Configuration Manager was setup was succesfully");
            } catch (ConfigManagerException exception) {
                configManager = null;
                throw exception;
            }
        }
    }

    /**
     * @param hv Hypervisor to check
     * @param hosts Hosts that belong on this hypervisor
     * @return
     */
    private static boolean checkVMsOnHV(OccpHV hv, Collection<OccpHost> hosts) {
        boolean Failure = false;
        boolean hasSetupNetwork = false;

        // First generate a list of actions
        final Map<String, Callable<OccpVM>> callables = new TreeMap<>();
        // This will hold the list of future items in the thread pool
        // We need to lookup the future item by name
        final Map<String, Future<OccpVM>> futureitems = new TreeMap<>();

        final class PhaseFinish implements Callable<OccpVM> {
            String vmname;
            OccpHV hv;
            int phase;

            PhaseFinish(String vmname_, OccpHV hv_, int phase_) {
                hv = hv_;
                vmname = vmname_;
                phase = phase_;
            }

            @Override
            public OccpVM call() throws OccpException {
                // At this point the VM should exist, and be on the setup network
                OccpVM vm = hv.getVM(vmname);
                hv.assignVMNetworks(vm, Arrays.asList(new String[] { setupNetworkName }));
                // rewrite dhcp file and notify dnsmasq
                createSetupDHCP();
                OccpHost host = parser.hosts.get(vmname);
                String ip = host.getSetupIP();
                if (!setup.connect(this.hv.getName())) {
                    logger.severe("Failed to setup the \"" + OccpParser.SETUPVPN_NAME + "\" VM on the hypervisor \""
                            + hv.getName() + '"');
                    return null;
                }

                for (int phase = this.phase; phase <= 2; ++phase) {
                    if (phase == 2 && host.getIntermediate()) {
                        break;
                    }
                    hv.powerOnVM(vm);

                    // Ensure we have a config manager setup
                    setupConfigManager();

                    logger.info("Applying phase " + phase + " to the VM \"" + host.getLabel()
                            + "\" on the hypervisor \"" + hv.getName() + '"');

                    boolean phaseApplied = false, hostnamePhaseApplied = false;
                    if (phase != 2) {
                        // Skip hostname phase if we aren't about to apply phase 2
                        hostnamePhaseApplied = true;
                    }
                    int phaseApplicationAttempts = 0;
                    while (!hostnamePhaseApplied || !phaseApplied) {
                        try {
                            phaseApplicationAttempts++;
                            if (!hostnamePhaseApplied) {
                                // Attempt hostname phase
                                logger.finer("Attempting to apply the hostname phase");
                                configManager.doPhase(host.getLabel(), "hostname", false);
                                hostnamePhaseApplied = true;
                            }
                            // Attempt phase
                            configManager.doPhase(host.getLabel(), "phase" + phase, true);

                            logger.info("The VM \"" + host.getLabel() + "\" on the hypervisor \"" + hv.getName()
                                    + "\" has just completed phase " + phase + " and should be powering off");

                            // Wait for the VM to power down
                            while (hv.isVMOn(vm)) {
                                logger.info("Waiting for the VM \"" + vmname + "\" (in phase " + phase
                                        + ") to poweroff: " + ip);
                                try {
                                    Thread.sleep(10000);
                                } catch (InterruptedException e) {
                                }
                            }
                            if (phase == 1) {
                                logger.info("Creating phase1 snapshot for the VM \"" + host.getLabel()
                                        + "\" on the hypervisor \"" + hv.getName() + '"');
                                // Create a snapshot for the phase we just completed
                                hv.createSnapshot(vm, "phase1");
                            }
                            phaseApplied = true;
                        } catch (ConfigManagerTemporaryFailureException exception) {
                            if (phaseApplicationAttempts % 10 == 0) {
                                logger.warning("Applying phase " + phase + " to the VM \"" + host.getLabel()
                                        + "\" on the hypervisor \"" + hv.getName() + "\" has now failed "
                                        + phaseApplicationAttempts + " times. Retrying...");
                            } else {
                                logger.fine("Applying phase " + phase + " to the VM \"" + host.getLabel()
                                        + "\" on the hypervisor \"" + hv.getName() + "\" has now failed "
                                        + phaseApplicationAttempts + " times. Retrying...");
                            }
                        }
                    }
                }
                return vm;
            }
        }

        final class IsoVM implements Callable<OccpVM> {
            OccpHV hv;
            String isoFile;
            String to;

            IsoVM(OccpHV hv_, String isoFile_, String to_) {
                hv = hv_;
                isoFile = isoFile_;
                to = to_;
            }

            @Override
            public OccpVM call() throws Exception {
                Thread.currentThread().setName("Import " + to);
                if (runMode.equals("verify")) {
                    logger.info("Would import " + to + " from " + isoFile + " on the hypervisor \"" + hv.getName()
                            + '"');
                    return null;
                }
                logger.info("Sending " + isoFile + " to the hypervisor \"" + hv.getName() + '"');
                setup.stageFile(hv.getName(), isoFile);
                logger.info("The hypervisor: \"" + hv.getName() + "\" received " + isoFile
                        + " and will now create the VM \"" + to + '"');
                hv.createVMwithISO(to, isoFile);
                OccpVM vm = hv.getVM(to);
                return vm;
            }
        }

        final class ImportVM implements Callable<OccpVM> {
            OccpHV hv;
            String from;
            String to;
            int phase;
            Callable<OccpVM> finish;

            ImportVM(OccpHV hv_, String from_, String to_, int phase_, Callable<OccpVM> finish_) {
                hv = hv_;
                from = from_;
                to = to_;
                phase = phase_;
                finish = finish_;
            }

            @Override
            public OccpVM call() throws Exception {
                Thread.currentThread().setName("Import " + to);
                if (runMode.equals("verify")) {
                    logger.info("Would import " + to + " from " + from + " on the hypervisor \"" + hv.getName() + '"');
                    return null;
                }
                logger.info("Sending " + from + " to the hypervisor \"" + hv.getName() + '"');
                setup.stageFile(hv.getName(), scenarioBaseDir + "/" + from);
                logger.info("The hypervisor: \"" + hv.getName() + "\" received " + from
                        + " and will now import it as the VM \"" + to + '"');
                hv.importVM(to, from);
                OccpVM vm = hv.getVM(to);
                if (phase == 1) {
                    logger.info("Creating phase1 snapshot for the VM \"" + to + "\" on the hypervisor \""
                            + hv.getName() + '"');
                    hv.createSnapshot(vm, "phase1");
                }
                if (this.finish != null) {
                    return finish.call();
                }
                return vm;
            }
        }

        final class CloneVM implements Callable<OccpVM> {
            OccpHV hv;
            String from;
            String to;
            int phase;
            Callable<OccpVM> finish;

            CloneVM(OccpHV hv_, String from_, String to_, int phase_, Callable<OccpVM> finish_) {
                hv = hv_;
                from = from_;
                to = to_;
                finish = finish_;
                phase = phase_;
            }

            @Override
            public OccpVM call() throws Exception {
                boolean isBase = (phase == 0);
                Thread.currentThread().setName("Clone " + to);
                OccpVM fromvm = null;
                if (futureitems.containsKey(from)) {
                    Future<OccpVM> future = futureitems.get(from);
                    if (future != null) {
                        fromvm = future.get();
                    } else {
                        logger.severe(from + " is not ready, can not clone to \"" + to + "\" on the hypervisor \""
                                + hv.getName() + '"');
                        return null;
                    }
                } else {
                    if (isBase) {
                        fromvm = hv.getBaseVM(from);
                    } else {
                        fromvm = hv.getVM(from);
                    }
                }
                if (runMode.equals("verify")) {
                    logger.info("Would deploy the VM \"" + to + "\" from \"" + from + "\" on the hypervisor \""
                            + hv.getName() + '"');
                    return null;
                }
                String snapshotBase;
                if (isBase) {
                    // Base VM doesn't have phase snapshots, but needs something for linked clones
                    snapshotBase = "linked";
                } else {
                    snapshotBase = "phase" + phase;
                }
                hv.cloneVM(fromvm, to, snapshotBase);
                OccpVM vm = hv.getVM(to);
                if (phase == 1) {
                    hv.createSnapshot(vm, "phase1");
                }
                if (finish != null) {
                    finish.call();
                }
                return vm;
            }
        }
        final class ExistingVM implements Callable<OccpVM> {
            Callable<OccpVM> finish;

            ExistingVM(Callable<OccpVM> finish_) {
                this.finish = finish_;
            }

            @Override
            public OccpVM call() throws Exception {
                OccpVM result = null;
                result = finish.call();
                return result;
            }
        }
        final class Done implements Callable<OccpVM> {
            OccpVM vm;

            Done(OccpVM vm_) {
                this.vm = vm_;
            }

            @Override
            public OccpVM call() {
                return vm;
            }
        }

        // Ensure this exists in case we need it
        if (!hasSetupNetwork && !hv.networkExists(setupNetworkName)) {
            try {
                hv.createNetwork(setupNetworkName);
            } catch (OccpException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                return false;
            }
        }
        hasSetupNetwork = true;

        for (OccpHost host : hosts) {
            try {
                // If the regen flag is set we need to roll back all applicable machines to their phase 1 snapshots
                // and then allow them to apply phase two. Machines without a phase 1 snapshot are not eligible for
                // regen.
                if (getRegenFlag() && !host.getLabel().equals(OccpParser.ROUTER_NAME)) {
                    try {
                        OccpVM currentVM = hv.getVM(host.getLabel());
                        if (hv.hasSnapshot(currentVM, "phase1")) {
                            // Has a phase 1 snapshot. Remove a phase 2 snapshot if it has one. Revert to phase 1
                            logger.info("Reverting " + host.getLabel() + " to phase 1 for regen on the hypervisor \""
                                    + hv.getName() + '"');
                            try {
                                hv.revertToSnapshot(currentVM, "phase1");
                                if (hv.hasSnapshot(currentVM, "phase2")) {
                                    logger.info("Removing old phase 2 snapshot for " + host.getLabel()
                                            + " as part of regen on the hypervisor \"" + hv.getName() + '"');
                                    hv.deleteSnapshot(currentVM, "phase2");
                                }
                            } catch (OccpException e) {
                                logger.log(Level.SEVERE, e.getMessage(), e);
                                Failure = true;
                            }
                        } else {
                            if (hv.hasSnapshot(currentVM, "phase2")) {
                                // Only has phase 2, revert to it
                                logger.info("Reverting " + host.getLabel() + " to phase 2 on the hypervisor \""
                                        + hv.getName() + "\"because it cannot be regenerated");
                                try {
                                    hv.revertToSnapshot(currentVM, "phase2");
                                } catch (OccpException e) {
                                    logger.log(Level.SEVERE, e.getMessage(), e);
                                    Failure = true;
                                }
                            } else {
                                // This machine does not have a phase 1 or 2 snapshot. Either it is a broken OCCP
                                // machine or not an OCCP machine at all
                                Failure = true;
                                logger.severe("During regen it was discovered that a VM labeled  \"" + host.getLabel()
                                        + "\" did not have any phase snapshots on the hypervisor \"" + hv.getName()
                                        + '"');
                            }
                        }
                    } catch (VMNotFoundException e) {
                        // Not all VM's need exist at start of regen
                    }
                }

                int phase = findVMPhase(hv, host);
                switch (phase) {
                case -1:
                    Failure = true;
                    logger.severe("Could not find a way to deploy the VM \"" + host.getLabel()
                            + "\" to the hypervisor: " + hv.getName());
                    break;
                case 0:
                    // Check that the base vm exists before proceeding
                    hv.getBaseVM(host.getBaseVM());
                    ensureDHCPRunning();
                    logger.info("Cloning the VM \"" + host.getBaseVM() + "\" to be VM \"" + host.getLabel()
                            + "\" on the hypervisor: " + hv.getName());

                    PhaseFinish phase1 = new PhaseFinish(host.getLabel(), hv, 1);
                    CloneVM futurevm = new CloneVM(hv, host.getBaseVM(), host.getLabel(), 0, phase1);
                    callables.put(host.getLabel(), futurevm);
                    break;
                case 1:
                    if (!host.getIntermediate()) {
                        ensureDHCPRunning();
                        PhaseFinish phase2 = new PhaseFinish(host.getLabel(), hv, 2);
                        try {
                            // If it already exists, just apply phase 2
                            hv.getVM(host.getLabel());
                            ExistingVM existingvm = new ExistingVM(phase2);
                            callables.put(host.getLabel(), existingvm);
                        } catch (VMNotFoundException e) {
                            // If this is a clone, do that, otherwise try to import it
                            if (host.getClone() != null) {
                                CloneVM futurevm2 = new CloneVM(hv, host.getClone(), host.getLabel(), 1, phase2);
                                callables.put(host.getLabel(), futurevm2);
                            } else {
                                ImportVM importvm = new ImportVM(hv, host.getOvaName(), host.getLabel(), phase, phase2);
                                callables.put(host.getLabel(), importvm);
                            }
                        }
                    } else {
                        try {
                            // This is an intermediate VM which has reached phase 1 so it is complete.
                            callables.put(host.getLabel(), new Done(hv.getVM(host.getLabel())));
                        } catch (VMNotFoundException e) {
                            // If the above fails because it doesn't exist, try importing it
                            ImportVM importvm = new ImportVM(hv, host.getOvaName(), host.getLabel(), phase, null);
                            callables.put(host.getLabel(), importvm);
                        }
                    }
                    break;

                case 2:
                    try {
                        OccpVM vm = hv.getVM(host.getLabel());
                        // Degenerate item to simplify code
                        callables.put(host.getLabel(), new Done(vm));
                    } catch (VMNotFoundException e) {
                        // If this is a clone, do that, otherwise try to import it
                        if (host.getClone() != null) {
                            callables.put(host.getLabel(), new CloneVM(hv, host.getClone(), host.getLabel(), 2, null));
                        } else if (host.getIsoName() != null) {
                            IsoVM importvm = new IsoVM(hv, host.getIsoName(), host.getLabel());
                            callables.put(host.getLabel(), importvm);
                        } else {
                            ImportVM importvm = new ImportVM(hv, host.getOvaName(), host.getLabel(), phase, null);
                            callables.put(host.getLabel(), importvm);
                        }
                    }
                    break;
                }
            } catch (OccpException e) {
                logger.log(Level.SEVERE, "Failed to start deploy of " + host.getLabel() + ": " + e.getMessage(), e);
                Failure = true;
                break;
            }
        }

        // Now we have generated the list of items to do in callables
        if (Failure == true) {
            // We've reported one or more errors, don't do any real work now
            return false;
        }

        assert callables.size() == hosts.size() : "Missing items";
        ExecutorCompletionService<OccpVM> ecs = new ExecutorCompletionService<>(exec);
        // Tell the thread pool to execute each item. Note that intermediates must be before clones
        for (Entry<String, Callable<OccpVM>> entry : callables.entrySet()) {
            futureitems.put(entry.getKey(), ecs.submit(entry.getValue()));
        }
        int vmDoneCount = 0;
        int vmFailedCount = 0;
        Set<String> doneVMs = new LinkedHashSet<>();
        Set<String> failedVMs = new LinkedHashSet<>();
        OccpHost host = null;
        while (vmDoneCount != hosts.size()) {
            OccpVM testvm = null;
            Future<OccpVM> futureitem;
            // Wait for each host to finish
            try {
                logger.info("Waiting for tasks to finish (" + exec.getActiveCount() + ")");
                futureitem = ecs.take();
            } catch (InterruptedException e1) {
                logger.log(Level.WARNING, "Interrupted", e1);
                break;
            }

            // Slightly expensive, but there aren't that many, and elsewhere we need lookup by name
            for (Entry<String, Future<OccpVM>> testfuture : futureitems.entrySet()) {
                if (testfuture.getValue() == futureitem) {
                    host = parser.hosts.get(testfuture.getKey());
                    break;
                }
            }
            if (host == null) {
                throw new RuntimeException("Logic error");
            }
            try {
                logger.info("Getting result of deploying " + host.getLabel());
                testvm = futureitem.get();

                if (!runMode.equals("verify")) {
                    try {
                        if (!host.getIntermediate() && !hv.hasSnapshot(testvm, "phase2")) {
                            logger.info("Finishing up deployment of " + hv.getName() + "/" + testvm.getName());
                            hv.assignVMNetworks(testvm, host.getPhyscialNetworkNames());
                            if (host.getRam() > 0) {
                                hv.assignVMRam(testvm, host.getRam());
                            }
                            if (!host.getLabel().equals(OccpParser.ROUTER_NAME)) {
                                hv.createSnapshot(testvm, "phase2");
                            }
                        }
                        logger.info("The VM \"" + host.getLabel() + "\" has been deployed on the hypervisor \""
                                + hv.getName() + '"');
                    } catch (OccpException e) {
                        logger.log(Level.SEVERE, e.getMessage(), e);
                        Failure = true;
                        ++vmFailedCount;
                    }
                }
            } catch (ExecutionException | InterruptedException e) {
                if (e.getCause() != null && e.getCause().getClass() == VMOperationFailedException.class) {
                    // Use our message directly
                    logger.log(Level.SEVERE, e.getCause().getMessage(), e.getCause());
                    Failure = true;
                } else {
                    logger.log(Level.SEVERE, "Failed to deploy " + host.getLabel(), e);
                    Failure = true;
                }
                failedVMs.add(host.getLabel());
                ++vmFailedCount;
            }
            doneVMs.add(host.getLabel());
            ++vmDoneCount;
            logger.info("Done with: " + StringUtils.join(doneVMs, ", "));
            logger.info("VMs Complete:" + vmDoneCount + "/" + hosts.size() + ", " + vmFailedCount + " Failed");
            if (!failedVMs.isEmpty()) {
                logger.info("Failed: " + StringUtils.join(failedVMs, ", "));
            }
        }
        return !Failure;
    }

    /**
     * Format a "raw" record for dnsmasq to represent an NS record
     * 
     * @param zoneName - Name of the zone for delegation
     * @param domainName - Name of the Name server
     * @return - Formatted record
     */
    private static String createNSRecord(String zoneName, String domainName) {
        StringBuilder result = new StringBuilder(zoneName);
        result.append(",2,");
        String[] domainParts = domainName.split("\\.");
        for (String part : domainParts) {
            result.append(Integer.toHexString(part.length()) + ":");
            for (byte c : part.getBytes()) {
                result.append(Integer.toHexString(c) + ":");
            }
        }
        result.append("00");
        return result.toString();
    }

    /**
     * Create a floppy image file for the Router
     * 
     * @param routerVM - Location of the router
     * @return
     * @throws OccpException
     */
    private static boolean createRouterFloppy(OccpHost routerVM) throws OccpException {
        FileSystem fs = mountFloppy(scenarioBaseDir.resolve("router.img").toString());
        if (fs == null) {
            logger.severe("Failed to create router floppy");
            return false;
        }
        boolean Failure = false;
        // We need to setup the IP and DNS information for "public" networks
        PrintStream autoconf, interfaces, dhcpcfg, dhcpopt, dnsmasqcfg, routercfg;
        try {
            autoconf = createFloppyFile(fs, "autoConfigure.sh");
            autoconf.println("cp /mnt/floppy/interfaces /etc/network/interfaces");
            autoconf.println("cp /mnt/floppy/*.conf /etc/");
            autoconf.println("/mnt/floppy/route.sh");

            // Using it's IP as gateway means "everything else"
            interfaces = createFloppyFile(fs, "interfaces");
            interfaces.println("auto lo");
            interfaces.println("iface lo inet loopback");
            interfaces.println("auto eth0");
            interfaces.println("iface eth0 inet static");
            // Should be set during config, too
            String routerip = "1.2.3.4";
            String routernetmask = "255.255.255.0";
            OccpHost gameserver = parser.hosts.get(OccpParser.GAMESERVER_NAME);
            if (gameserver != null) {
                for (OccpNetworkInterface gslink : gameserver.getInterfaces()) {
                    if (gslink.getNetwork().equals(OccpAdmin.RTR_GS_LINK)) {
                        routerip = gslink.getV4Gateway();
                        routernetmask = gslink.getV4Netmask();
                        break;
                    }
                }
            }
            interfaces.println(" address " + routerip);
            interfaces.println(" netmask " + routernetmask);
            // Use the self as the default gateway
            interfaces.println(" gateway " + routerip);
            // We don't configure the other interfaces here because
            // busybox doesn't seem to respect post-up to add routes

            dhcpcfg = createFloppyFile(fs, "dhcpd.conf");
            dhcpopt = createFloppyFile(fs, "dhcpdopts.conf");
            dnsmasqcfg = createFloppyFile(fs, "dnsmasq.conf");

            dnsmasqcfg.println("dhcp-hostsfile=/etc/dhcpd.conf");
            dnsmasqcfg.println("dhcp-optsfile=/etc/dhcpdopts.conf");
            // We don't want anyone stealing root names; we'll do DNS ourself
            dnsmasqcfg.println("dhcp-ignore-names");
            // Become the root-nameserver (i.e., resolve all names locally)
            Set<String> localTlds = new HashSet<>();
            localTlds.add("arpa");
            localTlds.add("com");
            localTlds.add("edu");
            localTlds.add("net");
            localTlds.add("org");
            // Log information for debug purposes
            dnsmasqcfg.println("log-dhcp");
            dnsmasqcfg.println("log-queries");

            // Generate entries for rootdns
            for (OccpDNSEntry entry : parser.dns) {
                switch (entry.entryType) {
                case "MX":
                    dnsmasqcfg.println("mx-host=" + entry.entryName + "," + entry.entryValue + ",1");
                    break;
                case "A":
                    // TODO: AAAA records need to be added here, too
                    dnsmasqcfg.println("host-record=" + entry.entryName + "," + entry.entryValue);
                    String tld = entry.entryName.substring(entry.entryName.lastIndexOf('.'));
                    localTlds.add(tld);
                    break;
                case "PTR":
                    // Note: host-record will generate a PTR record automatically
                    dnsmasqcfg.println("ptr-record=" + entry.entryName + "," + entry.entryValue);
                    break;
                case "CNAME":
                    dnsmasqcfg.println("cname=" + entry.entryName + "," + entry.entryValue);
                    break;
                case "TXT":
                    dnsmasqcfg.println("txt-record=" + entry.entryName + "," + entry.entryValue);
                    break;
                case "SRV":
                    // entryName should look like _service._tcp.domain.tld
                    // entryValue should look like "ip,port,priority,weight"
                    dnsmasqcfg.println("srv-host=" + entry.entryName + "," + entry.entryValue);
                    break;
                case "NS":
                    dnsmasqcfg.println("dns-rr=" + createNSRecord(entry.entryName, entry.entryValue));
                    break;
                }
            }
            for (String tld : localTlds) {
                dnsmasqcfg.println("local=/" + tld + "/");
            }

            // We also need to generate the routing table
            routercfg = createFloppyFile(fs, "route.sh");
            // octet is used in generating routing address for the "ISP"
            // (RouterVM)
            int octet = 1;
            for (OccpHost host : parser.hosts.values()) {
                if (host.getIntermediate()) {
                    continue;
                }
                if (host.getLabel() == OccpParser.ROUTER_NAME) {
                    continue;
                }
                int iFaceNumber = 0;
                int routableIface = -1;
                for (OccpNetworkInterface ip : host.getInterfaces()) {
                    if (ip.getType() == InterfaceType.PHYSICAL) {
                        // Note that these names were generated from buildTopologyInformation
                        if (ip.getNetwork().startsWith("rtr-")) {
                            String ifaddr = null, ifnetmask = null, ifcidr = null, ifgateway, ifrouter;
                            String genrouter = "12.14.16." + octet;
                            if (!ip.hasV4Address()) {
                                ifaddr = "12.14.16." + (octet + 1);
                                ifnetmask = "255.255.255.252";
                                ifgateway = genrouter;
                                ifrouter = genrouter + "/30";
                            } else {
                                ifaddr = ip.getV4Address();
                                ifnetmask = ip.getV4Netmask();
                                ifgateway = ip.getV4Gateway();
                                if (ifgateway == null) {
                                    SubnetUtils ifutil = new SubnetUtils(ifaddr, ifnetmask);
                                    ifgateway = ifutil.getInfo().getLowAddress();
                                    if (ifaddr.equals(ifgateway)) {
                                        failure = true;
                                        logger.severe("Could not find gatway for machine/link " + host.getLabel() + "/"
                                                + ip.getName());
                                    }

                                }
                                SubnetUtils gwutil = new SubnetUtils(ifgateway, ifnetmask);
                                ifrouter = gwutil.getInfo().getCidrSignature();
                            }
                            // Construct a SubnetUtils so we can compute network/cidr
                            SubnetUtils hostutil = new SubnetUtils(ifaddr, ifnetmask);
                            SubnetUtils.SubnetInfo iphost = hostutil.getInfo();
                            String netaddr = iphost.getNetworkAddress();
                            SubnetUtils netutil = new SubnetUtils(netaddr, ifnetmask);
                            ifcidr = netutil.getInfo().getCidrSignature();
                            routableIface = iFaceNumber;
                            /*
                             * routableIface is the interface we need to get the
                             * mac from to put in the dhcp file
                             */
                            // Find the correct hypervisor for this VM
                            OccpHV vmhv = hvs.get(vm2hv.get(host.getLabel()));
                            OccpVM vm;
                            try {
                                vm = vmhv.getVM(host.getLabel());
                            } catch (VMNotFoundException e) {
                                // unlikely, since we should have found it already
                                logger.severe("Failed to find VM " + host.getLabel());
                                Failure = true;
                                continue;
                            }
                            String mac = vmhv.getVMMac(vm, routableIface);
                            octet += 4;
                            /* dhcphosts file for dnsmasq */
                            dhcpcfg.println(mac + ",set:" + host.getLabel() + "," + ifaddr);
                            dhcpopt.println("tag:" + host.getLabel() + ",option:router," + ifgateway);
                            dnsmasqcfg.println("dhcp-range=" + ifaddr + "," + ifaddr);

                            int ethIndex = 0;
                            for (OccpNetworkInterface testIf : routerVM.getInterfaces()) {
                                if (testIf.getNetwork().equals(host.getInterfaces().get(routableIface).getNetwork())) {
                                    break;
                                }
                                ethIndex += 1;
                            }
                            String ethName = "eth" + ethIndex;

                            // TODO: Remove this block
                            routercfg.println("set -o xtrace");

                            routercfg.println("ip addr add " + ifrouter + " dev " + ethName);
                            routercfg.println("ip link set " + ethName + " up");
                            for (String route : ip.getRoutes()) {
                                /*
                                 * Break down and rebuild the address to ensure the IP portion is using the network
                                 * address. The "ip route" command will fail otherwise
                                 */
                                SubnetUtils routeutil = new SubnetUtils(route);
                                SubnetUtils.SubnetInfo routeinfo = routeutil.getInfo();
                                String routenetaddr = routeinfo.getNetworkAddress();
                                String routenetnetmask = routeinfo.getNetmask();
                                SubnetUtils routenetutil = new SubnetUtils(routenetaddr, routenetnetmask);
                                String routecidr = routenetutil.getInfo().getCidrSignature();
                                routableIface = iFaceNumber;
                                routercfg.println("ip route add " + routecidr + " via " + ifaddr + " dev " + ethName);
                            }
                        }
                        ++iFaceNumber;
                    }
                }
            }
            dhcpcfg.close();
            dhcpopt.close();
            dnsmasqcfg.close();
            routercfg.close();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to create router config file", e);
            Failure = true;
        }
        unmountFloppy(fs);

        return !Failure;
    }

    /**
     * Write the openvpn.conf file for a given VPN
     * 
     * @param conf - Stream to write configuration to
     * @param ca - Certificate Authority object
     * @param caKey - CA Key
     * @param tapName - Name of the 'tap' device for this VPN
     * @param brName - Name of the 'bridge' device for this VPN
     * @param serverPort - Port for the server
     * @param serverIP - Server IP for client's use (optional)
     * @param upshpath - Path to script for interface connection
     * @throws IOException
     * @throws CertificateException
     * @throws OperatorCreationException
     * @throws InvalidAlgorithmParameterException
     * @throws NoSuchProviderException
     * @throws NoSuchAlgorithmException
     */
    private static void writeVPNConf(PrintStream conf, CA ca, CertPair caKey, String tapName, String brName,
            int serverPort, String serverIP, String upshpath)
            throws IOException, NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException,
            OperatorCreationException, CertificateException {
        conf.print("comp-lzo\npersist-tun\nmute 5\nverb 3\nscript-security 2\n");
        conf.println("dev " + tapName);
        conf.println("up \"" + upshpath + " " + brName + "\"\n");
        String certDN = null;
        if (serverIP != null) { // client
            // Static part
            conf.print("proto tcp-client\nauth-nocache\ntls-client\nclient\nnobind\n");
            // Dynamic part
            conf.println("remote " + serverIP + " " + serverPort);
            certDN = "CN=client" + (++clientNum);
        } else {
            // Static part
            conf.print("proto tcp-server\nmode server\ntls-server\nclient-to-client\nuser nobody\n"
                    + "group nogroup\nkeepalive 10 600\npersist-key\n" + "dh dh1024.pem\n");
            // Dynamic part
            conf.println("status /tmp/" + tapName + "-status.log");
            conf.println("port " + serverPort);
            conf.println("<dh>");
            failure |= !ca.genDHParams(conf);
            conf.println("</dh>");
            certDN = "CN=server" + (++serverNum);
        }
        conf.println("<ca>");
        ca.writeCert(caKey.cert, conf);
        conf.println("</ca>");
        CA.CertPair kp = ca.generateCert(certDN, serverIP == null);
        conf.println("<cert>");
        ca.writeCert(kp.cert, conf);
        conf.println("</cert>");
        conf.println("<key>");
        ca.writeKey(kp.key, conf);
        conf.println("</key>");
    }

    /**
     * Generates the configuration floppy for a VPN on a given HV
     * 
     * @param hv - HV to generate it for
     * @param vpnNetworks - Networks that are required
     * @return
     */
    static boolean createVPNFloppy(String hv, ArrayList<String> vpnNetworks) {
        String vpnip = hv2vpnip.get(hv);
        String vpngw = hv2vpngw.get(hv);
        FileSystem fs = mountFloppy(scenarioBaseDir.resolve(hv + ".img").toString());
        if (fs == null) {
            return false;
        }
        try {
            PrintStream autoconf = createFloppyFile(fs, "autoConfigure.sh");
            // Some old VPN iso's have this running as a debug tool
            autoconf.println("killall dropbear");
            autoconf.println("cp /mnt/floppy/interfaces /etc/network/interfaces");
            autoconf.println("mkdir /etc/openvpn/");
            autoconf.println("cp /mnt/floppy/*.conf /etc/openvpn/");
            autoconf.println("cp /mnt/floppy/up.sh /etc/openvpn/");
            autoconf.close();

            PrintStream interfaces = createFloppyFile(fs, "interfaces");
            interfaces.println("auto lo");
            interfaces.println("iface lo inet loopback");
            interfaces.println("auto eth0");
            // Only the server side needs a static address
            if (vpnip == null) {
                interfaces.println("iface eth0 inet dhcp");
            } else {
                interfaces.println("iface eth0 inet static");
                interfaces.println(" address " + vpnip);
                // Maybe should parse IP for cidr?
                interfaces.println("netmask 255.255.255.0");
                if (vpngw != null) {
                    interfaces.println(" gateway " + vpngw);
                }
            }
            int x = 0;

            // Generate blocks for each bridge and associated network
            for (String net : vpnNetworks) {
                String ifaceName = "eth" + (x + 1);
                // String brName = "br" + x;
                String brName = net;
                interfaces.println("#" + net);
                interfaces.println("auto " + ifaceName);
                interfaces.println("iface " + ifaceName + " inet manual");
                interfaces.println("   up ifconfig " + ifaceName + " 0.0.0.0 promisc up");

                interfaces.println("auto " + brName);
                interfaces.println("iface " + brName + " inet static");
                interfaces.println("   pre-up brctl addbr " + brName);
                interfaces.println("   pre-up brctl addif " + brName + " " + ifaceName);
                interfaces.println("   address 0.0.0.0");
                interfaces.println("   netmask 128.0.0.0");

                String fileName = net + ".conf";
                String tapName = "tap" + x;
                VpnConnection vpn = net2vpn.get(net);
                String serverIP;
                if (vpnip == null || !vpnip.equals(vpn.ip)) {
                    serverIP = vpn.ip;
                } else {
                    serverIP = null;
                }

                PrintStream conf = createFloppyFile(fs, fileName);
                writeVPNConf(conf, vpn.ca, vpn.cp, tapName, brName, vpn.port, serverIP, "/etc/openvpn/up.sh");
                conf.close();
                ++x;
            }
            interfaces.close();
            PrintStream upsh = createFloppyFile(fs, "up.sh");
            // Static contents, for now
            upsh.println("#!/bin/sh\n" + "PATH=/sbin:/usr/sbin:/bin:/usr/bin\n" + "BR=$1\n" + "DEV=$2\n" + "MTU=$3\n"
                    + "ifconfig \"$DEV\" up promisc mtu \"$MTU\"\n"
                    + "if ! brctl show $BR | egrep -q \"\\W+$DEV$\"; then\n" + "   brctl addif $BR $DEV\n" + "fi\n");
            upsh.close();
        } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException
                | OperatorCreationException | CertificateException e) {
            logger.log(Level.SEVERE, "Error generating VPN certificate", e);
            return false;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error writing VPN configuration", e);
            return false;
        } finally {
            unmountFloppy(fs);
        }
        return true;
    }

    /**
     * Generates the configuration files for the setup VPN on a given HV
     * 
     * @param hv - HV to generate it for
     * @param vpnip - IP to use for server side, or null
     * @param vpn - Details for VPN
     * @return
     */
    static boolean createSetupVPNFiles(String hv, String vpnip, VpnConnection vpn) {
        java.nio.file.FileSystem fs = FileSystems.getDefault();
        Path dir = occpHiddenDirPath.resolve(hv);
        Set<PosixFilePermission> defaultPerms = new HashSet<>();
        defaultPerms.add(PosixFilePermission.OWNER_READ);
        defaultPerms.add(PosixFilePermission.OWNER_WRITE);
        defaultPerms.add(PosixFilePermission.OWNER_EXECUTE);
        try {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(defaultPerms));
            }
            Path upshpath;
            int x = 0;
            String brName = "br" + x;
            Path fileName = fs.getPath(dir.toString(), setupNetworkName + ".conf");
            String tapName = "tap" + x;
            String serverIP;
            if (vpnip == null) {
                serverIP = vpn.ip;
                upshpath = fs.getPath("/etc/openvpn/up.sh");
            } else {
                serverIP = null;
                upshpath = fs.getPath(dir.toString(), "up.sh");
            }
            PrintStream conf = new PrintStream(Files.newOutputStream(fileName, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            writeVPNConf(conf, vpn.ca, vpn.cp, tapName, brName, vpn.port, serverIP, upshpath.toString());
            conf.close();
            PrintStream upsh = new PrintStream(Files.newOutputStream(fs.getPath(dir.toString(), "up.sh"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            // Static contents, for now
            upsh.println("#!/bin/sh\n" + "PATH=/sbin:/usr/sbin:/bin:/usr/bin\n" + "BR=$1\n" + "DEV=$2\n" + "MTU=$3\n"
                    + "ifconfig \"$DEV\" up promisc mtu \"$MTU\"\n"
                    + "if ! brctl show $BR | egrep -q \"\\W+$DEV$\"; then\n" + "   brctl addif $BR $DEV\n" + "fi\n");
            upsh.close();
            Files.setPosixFilePermissions(dir.resolve("up.sh"), defaultPerms);
        } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException
                | OperatorCreationException | CertificateException e) {
            logger.log(Level.SEVERE, "Error generating VPN certificate", e);
            return false;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error writing VPN configuration", e);
            return false;
        }
        return true;
    }

    /**
     * Setup hv2net and net2hv
     * hv2net will give a list of networks on each hypervisor
     * net2hv will give a list of hypervisors a network is on
     */
    private static void buildTopologyInformation() {
        hv2net = new TreeMap<String, Set<OccpNetwork>>();
        net2hv = new TreeMap<String, Set<String>>();
        if (parser.routerNeeded) {
            OccpHost routerhost = parser.hosts.get(OccpParser.ROUTER_NAME);
            // First make a pass through all hosts and setup public routing
            int routerLinks = 0;
            if (parser.hosts.containsKey(OccpParser.GAMESERVER_NAME)) {
                OccpNetworkInterface gsnet = new OccpNetworkInterface("eth0",
                        OccpNetworkInterface.InterfaceType.PHYSICAL, OccpNetworkInterface.Config.NONE);
                gsnet.setNetwork(RTR_GS_LINK);
                // Always at least connect to the game server
                routerhost.addInterface(gsnet);
                OccpNetwork gameserverNetwork = new OccpNetwork();
                gameserverNetwork.setLabel(RTR_GS_LINK);
                parser.networks.put(RTR_GS_LINK, gameserverNetwork);
            }

            for (OccpHost vm : parser.hosts.values()) {
                if (vm.getIntermediate()) {
                    continue;
                }
                for (OccpNetworkInterface network : vm.getPhyscialInterfaces()) {
                    if (network.getNetwork().equals("fake-internet")) {
                        String linkName = "rtr-link-" + routerLinks;
                        ++routerLinks;
                        // Replace the name of this network on the host
                        network.setNetwork(linkName);
                        // Add this new link to the router as well
                        OccpNetworkInterface newnet = new OccpNetworkInterface("eth" + routerLinks,
                                OccpNetworkInterface.InterfaceType.PHYSICAL, OccpNetworkInterface.Config.NONE);
                        newnet.setNetwork(linkName);
                        routerhost.addInterface(newnet);
                        // Create a new Network on the HV
                        OccpNetwork routerLink = new OccpNetwork();
                        routerLink.setLabel(linkName);
                        parser.networks.put(linkName, routerLink);
                    }
                }
            }
        }
        for (String name : hv2vm.keySet()) {
            for (OccpHost vm : hv2vm.get(name)) {
                if (vm.getIntermediate()) {
                    continue;
                }
                for (OccpNetworkInterface network : vm.getPhyscialInterfaces()) {
                    Set<OccpNetwork> s;
                    OccpNetwork net = parser.networks.get(network.getNetwork());
                    if (net == null) {
                        logger.severe("Host " + vm.getLabel() + " references non-existent network "
                                + network.getNetwork());
                        failure = true;
                        continue;
                    }

                    // Construct a map of which networks belong on each HV
                    if ((s = hv2net.get(name)) != null) {
                        s.add(net);
                    } else {
                        s = new TreeSet<OccpNetwork>();
                        s.add(net);
                        hv2net.put(name, s);
                    }
                    // Also construct a map of which HV's each network is on
                    // This is used to determine if we need to setup VPNs
                    Set<String> shv;
                    if ((shv = net2hv.get(network.getNetwork())) != null) {
                        shv.add(name);
                    } else {
                        shv = new TreeSet<String>();
                        shv.add(name);
                        net2hv.put(network.getNetwork(), shv);
                    }
                }
            }
        }
    }

    /**
     * Parse the map file to determine which hypervisors VMs need to be on. Clones will also force their intermediate to
     * be on the hypervisor but should not specify them directly. When clones are placed on multiple hypervisors their
     * intermediates will require unqiue hostnames. "Unofficial clones" i.e. ones that are not made with the clone
     * attribute but are just specified on multiple hypervisors are not allowed.
     * 
     * @return True if there were no errors, false otherwise
     */
    private static boolean parsePhysMap() {
        boolean failure = false;
        hv2vpnip = new HashMap<String, String>();
        hv2vpngw = new HashMap<String, String>();
        ArrayList<String> encounteredIntermediates = new ArrayList<>();
        ArrayList<String> encounteredVMLabels = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(new FileReader(hvMap));
            String line;
            while ((line = reader.readLine()) != null) {
                String hvname, vpnip, vpngw;
                String[] data = line.split(":");
                if (data.length != 2) {
                    logger.severe("Could not parse line in map file: " + line);
                    failure = true;
                    continue;
                }
                List<OccpHost> vms = new ArrayList<OccpHost>();
                if (data[0].contains("/")) {
                    // The line appears to have an IP in it, extract the hv name and ip
                    String[] hvip = data[0].split("/");
                    hvname = hvip[0];
                    vpnip = hvip[1];
                    if (vpnip.contains(",")) {
                        String[] ipgw = vpnip.split(",");
                        vpnip = ipgw[0];
                        vpngw = ipgw[1];
                        hv2vpngw.put(hvname, vpngw);
                    }
                    hv2vpnip.put(hvname, vpnip);
                } else {
                    // Did not contain an IP, extract the name
                    hvname = data[0].trim();
                }

                // For each comma separated VM label
                for (String s : Arrays.asList(data[1].split(","))) {
                    String trimmedLabel = s.trim();
                    // Don't add empty entries
                    if (trimmedLabel.length() > 1) {
                        // Look up the VM to make sure it was specified in the XML
                        OccpHost vm = parser.hosts.get(trimmedLabel);
                        if (vm != null) {
                            if (!vm.getIntermediate()) {
                                vm2hv.put(trimmedLabel, hvname);
                                // The VM we need to clone also needs to be on this HV
                                if (vm.getClone() != null) {
                                    // It is possible that the intermediate's clones ended up on multiple hypervisors.
                                    // This is problematic because the intermediate may be setup in two locations. This
                                    // would be resolved by having unique hostnames in all locations.
                                    OccpHost clonedFrom = parser.hosts.get(vm.getClone());
                                    String uniqueLabel = clonedFrom.getLabel() + '-' + hvname;
                                    OccpHost uniqueIntermediate = parser.hosts.get(uniqueLabel);
                                    if (uniqueIntermediate == null) {
                                        // We do not have the unique intermediate on this HV, we must construct it
                                        uniqueIntermediate = clonedFrom.makeClone(uniqueLabel);
                                        uniqueIntermediate.setIntermediate(true);

                                        // Add it to our list of hosts
                                        if (!parser.addHost(uniqueIntermediate)) {
                                            logger.severe("Failed to add \"" + uniqueLabel + "\" to list of hosts");
                                            failure = true;
                                        }

                                        // Add to our map of machine labels to HVs
                                        vm2hv.put(uniqueLabel, hvname);

                                        // Add to our overall list of VMs
                                        vms.add(uniqueIntermediate);

                                        // Make sure there are no duplicates, in this particular case there shouldn't
                                        // ever be but "better safe than sorry"
                                        if (!encounteredVMLabels.contains(uniqueLabel)) {
                                            encounteredVMLabels.add(uniqueLabel);
                                        } else {
                                            logger.severe("The VM \"" + uniqueLabel
                                                    + "\" was specified more than once in your map");
                                            failure = true;
                                        }

                                        // Keep track of the intermediates we had to make unique as we will need to
                                        // remove them after. Nothing will end up needing them.
                                        if (!encounteredIntermediates.contains(clonedFrom.getLabel())) {
                                            encounteredIntermediates.add(clonedFrom.getLabel());
                                        }
                                    }
                                    // Update the clone to point to the unique intermediate
                                    vm.setClone(uniqueLabel);
                                }
                                // Add to our overall list of VMs
                                vms.add(vm);
                                // Make sure there are no duplicates
                                if (!encounteredVMLabels.contains(trimmedLabel)) {
                                    encounteredVMLabels.add(trimmedLabel);
                                } else {
                                    logger.severe("The VM \"" + trimmedLabel
                                            + "\" was specified more than once in your map");
                                    failure = true;
                                }
                            } else {
                                logger.severe("The intermediate VM \"" + trimmedLabel
                                        + "\" should not be specified in your map file");
                                failure = true;
                            }
                        } else {
                            logger.severe("Didn't find " + trimmedLabel + " in the configuration file");
                            failure = true;
                        }
                    }
                }
                hv2vm.put(hvname, vms);
            }
            reader.close();
        } catch (FileNotFoundException e) {
            logger.severe("Could not find specified hvMap " + hvMap);
            return false;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error reading map file: " + hvMap, e);
            return false;
        }

        // Remove old intermediates because all their clones will be referencing uniquely named intermediates on the
        // various hypervisors instead of these intermediates
        for (String oldIntermediate : encounteredIntermediates) {
            parser.removeHost(oldIntermediate);
        }

        // Display the result
        Set<String> testSet = new HashSet<>();
        testSet.addAll(parser.hosts.keySet());
        for (Entry<String, List<OccpHost>> entry : hv2vm.entrySet()) {
            // Display the HV name
            logger.info(entry.getKey() + ":");
            // Display the VMs on the HV
            for (OccpHost currentHost : entry.getValue()) {
                logger.info("\t" + currentHost.getLabel());
                testSet.remove(currentHost.getLabel());
            }
        }

        // Determine if all XML specified VMs were mapped
        for (String hostLabel : testSet) {
            OccpHost testHost = parser.hosts.get(hostLabel);
            if (testHost.getIntermediate()) {
                // This would imply that the intermediate's clones weren't listed in the map
                continue;
            }
            logger.severe("Didn't find the VM \"" + hostLabel + "\" in the map");
            failure = true;
        }
        return !failure;
    }

    /**
     * Create the configuration for dnsmasq used during setup
     * 
     * @return
     * @throws OccpException
     */
    private synchronized static boolean createSetupDHCP() throws OccpException {
        boolean Failure = false;
        // We need to setup the IP and DNS information for "public" networks
        PrintStream dhcpcfg = null, dnsmasqcfg = null;
        try {
            File dhcp_f = occpHiddenDirPath.resolve("dhcpd.conf").toFile();
            File dnsmasqcfg_f = occpHiddenDirPath.resolve("dnsmasq.conf").toFile();

            dhcpcfg = new PrintStream(dhcp_f);
            dnsmasqcfg = new PrintStream(dnsmasqcfg_f);

            // We expect the AdminVM to have two interfaces; the first is the
            // public one to interact with the HVs
            // The second is an internal only one that is used to interact with
            // the VMs
            dnsmasqcfg.println("interface=br0");
            dnsmasqcfg.println("listen-address=12.14.16.1");
            dnsmasqcfg.println("dhcp-hostsfile=" + dhcp_f.toString());
            // dnsmasqcfg.println("dhcp-optsfile=dhcpdopts.conf");
            dnsmasqcfg.println("dhcp-range=12.14.16.20,12.14.16.100");
            // We don't want anyone stealing root names; we'll do DNS ourself
            dnsmasqcfg.println("dhcp-ignore-names");
            dnsmasqcfg.println("bind-dynamic");
            // Log information for debug purposes
            dnsmasqcfg.println("log-dhcp");
            dnsmasqcfg.println("leasefile-ro");
            dnsmasqcfg.println("dhcp-option=option:router,12.14.16.1");
            dnsmasqcfg.println("dhcp-option=option:dns-server,12.14.16.1");
            // Clients need to be able to resolve "puppet" to find the puppet master
            dnsmasqcfg.println("address=/puppet/12.14.16.1");

            for (OccpHost host : parser.hosts.values()) {
                if (host.getLabel() == OccpParser.ROUTER_NAME) {
                    continue;
                }
                // Find the correct hypervisor for this VM
                OccpHV vmhv = hvs.get(vm2hv.get(host.getLabel()));
                OccpVM vm;
                try {
                    vm = vmhv.getVM(host.getLabel());
                } catch (VMNotFoundException e) {
                    // This is expected because not all hosts will be ready
                    continue;
                }
                String mac = vmhv.getVMMac(vm, 0);
                // Slight limitation of 253 machines
                if (host.getSetupIP() == null) {
                    String genaddr = "12.14.16." + nextOctet;
                    nextOctet += 1;
                    host.setSetupIP(genaddr);
                }
                dhcpcfg.println(mac + ",set:" + host.getLabel() + "," + host.getSetupIP());
                dnsmasqcfg.println("host-record=" + host.getLabel() + "," + host.getSetupIP());
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to write DHCP configuration", e);
            Failure = true;
        } finally {
            if (dhcpcfg != null) {
                dhcpcfg.close();
            }
            if (dnsmasqcfg != null) {
                dnsmasqcfg.close();
            }
        }
        // Assume we are being called on a refresh
        // Java provides no portable facility for generating signals
        Runtime rt = Runtime.getRuntime();
        String[] cmd = { "sudo", "pkill", "-HUP", "dnsmasq" };
        try {
            rt.exec(cmd);
        } catch (IOException e) {
            if (mondhcp.isRunning()) {
                logger.warning("Failed to tell dnsmasq to refresh config");
            }
        }
        return !Failure;
    }

    private static boolean parseGlobalConfig(String filename) {
        globalConfig = new Properties();
        try (BufferedReader configFile = new BufferedReader(new FileReader(filename))) {
            globalConfig.load(configFile);
        } catch (FileNotFoundException e) {
            // Not fatal
            logger.info("Warning: No global configuration file found, using defaults (" + filename + ")");
        } catch (IOException e) {
            logger.severe("Error reading configuration file:" + filename);
            return false;
        }
        // Pre-fetch a things that are used in several places
        setupNetworkName = globalConfig.getProperty("setupNetworkName", "OCCP_Setup");
        setupinterface = globalConfig.getProperty("setupinterface", "eth1");
        return true;
    }

    /**
     * Writes any reports created
     * 
     * @param reports - The map of report names and content
     * @return Whether or not all reports were written successfully.
     */
    private static boolean writeReports(HashMap<String, String> reports) {
        boolean encounteredError = false;
        String reportPath = scenarioBaseDir + "/Reports";
        encounteredError = !safeMkdir(reportPath);
        if (!encounteredError && instanceId != null) {
            reportPath += "/" + instanceId;
            encounteredError = !safeMkdir(reportPath);
        }
        logger.info("Report directory: " + reportPath);

        if (!encounteredError) {
            // For each report, try and write the file
            for (Entry<String, String> entry : reports.entrySet()) {
                String reportName = entry.getKey();
                String report = entry.getValue();
                File reportFile = new File(reportPath + '/' + reportName);
                try {
                    logger.info("Writing report: " + reportName);
                    FileWriter lastrunWriter = new FileWriter(reportFile, false);
                    lastrunWriter.write(report);
                    lastrunWriter.close();
                } catch (IOException exception) {
                    logger.log(Level.SEVERE, "Unable to write report file " + reportName, exception);
                    encounteredError = true;
                }
            }
        }
        return !encounteredError;
    }

    /**
     * @param dirPath - Path to create
     * @return - Success/failure
     */
    private static boolean safeMkdir(String dirPath) {
        File reportDirectory = new File(dirPath);

        if (!reportDirectory.exists()) {
            // Try and make the directory
            logger.fine("Creating the reports directory because it did not exist: " + dirPath);
            if (!reportDirectory.mkdir()) {
                // Unable to make the directory
                logger.severe("Unable to create " + dirPath);
                return false;
            }
        } else {
            // It existed but is it a directory?
            if (!reportDirectory.isDirectory()) {
                // Was not a directory
                logger.severe("Expects " + dirPath + " to be a directory but it wasn't");
                return false;
            }
        }
        return true;
    }

    private static class Packager extends SimpleFileVisitor<Path> {
        private final TarArchiveOutputStream pkg;

        public Packager(TarArchiveOutputStream pkg) {
            this.pkg = pkg;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            List<String> excludeFiles = Arrays.asList(new String[] { "instance.xml", "lastrun.pp", "router.img",
                    scenarioName + ".tar" });
            Path rel = scenarioBaseDir.relativize(file);
            if (rel.getParent() == null && excludeFiles.contains(file.getFileName().toString())) {
                // Don't include this archive in itself
                return FileVisitResult.CONTINUE;
            }
            List<String> excludeDirs = Arrays.asList(new String[] { "Reports" });
            if (rel.getParent() != null && excludeDirs.contains(rel.getParent().toString())) {
                // Don't include this archive in itself
                return FileVisitResult.CONTINUE;
            }
            TarArchiveEntry entry = null;
            if (file.getFileName().toString().endsWith(".ova")) {
                // We only want exported ova files
                if (file.getParent().getFileName().toString().equals("Export")) {
                    entry = (TarArchiveEntry) pkg.createArchiveEntry(file.toFile(), scenarioName + "/"
                            + file.getFileName().toString());
                }
            } else {
                entry = (TarArchiveEntry) pkg.createArchiveEntry(file.toFile(),
                        scenarioBaseDir.getParent().relativize(file).toString());
            }
            if (entry != null) {
                logger.finest("Adding " + entry.getName() + " to export package");
                pkg.putArchiveEntry(entry);
                pkg.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
                File localFile = file.toFile();
                if (localFile.isFile()) {
                    IOUtils.copy(new FileInputStream(localFile), pkg);
                    pkg.closeArchiveEntry();
                } else {
                    // Directories have no information
                    pkg.closeArchiveEntry();
                }
            }
            return FileVisitResult.CONTINUE;
        }
    }

    /**
     * Implements the "export" mode. This is accomplished by reverting each specified machine to their appropriate
     * snapshots. For efficiency clones are not exported, only their intermediate VM is. Whenever possible VMs will be
     * exported at phase 1.
     * 
     * @return true if successful, false otherwise.
     * @throws VMNotFoundException
     * @throws OccpException
     */
    private static boolean doExport() throws VMNotFoundException, OccpException {
        if (!safeMkdir(scenarioBaseDir.resolve("Export").toString())) {
            return false;
        }
        setup = new SetupNetwork(hvs);
        setup.stop();
        if (!setup.createConfiguration()) {
            logger.severe("Error configuring setup VPN");
            return false;
        }
        // First check that we meet the conditions for export so we could fail early if something is not ready to export
        for (OccpHost host : parser.getOccpHosts()) {
            if (host.getClone() != null || host.getIsoName() != null || host.getLabel().equals(OccpParser.ROUTER_NAME)) {
                // Skip clones, ISOs and the router
                continue;
            }
            logger.info("Checking the VM \"" + host.getLabel() + "\" for export readiness.");
            OccpHV hv = hvs.get(vm2hv.get(host.getLabel()));

            try {
                OccpVM vm = hv.getVM(host.getLabel());
                // Determine and revert to the appropriate snapshot
                if (hv.hasSnapshot(vm, "phase1")) {
                    // If a VM has a phase1 this should be what it is exported from
                    hv.revertToSnapshot(vm, "phase1");
                } else if (hv.hasSnapshot(vm, "phase2")) {
                    // If we only have a phase2 then we have no choice but to export from it
                    hv.revertToSnapshot(vm, "phase2");
                } else {
                    // If there are not phase snapshots, this is probably a misconfigured OCCP machine or not OCCP
                    // related
                    logger.severe("Error: the VM \"" + host.getLabel() + "\" does not have a phase snapshot to export");
                    failure = true;
                    // Keep going so we can get all errors in one pass
                    continue;
                }
            } catch (VMNotFoundException e) {
                logger.severe("Unable to find the VM \"" + host.getLabel() + "\" for exporting");
                failure = true;
                // Keep going so we can get all errors in one pass
                continue;
            }
        }
        if (!failure) {
            // Each machine is ready for export, now try the export
            for (OccpHost host : parser.getOccpHosts()) {
                if (host.getClone() != null || host.getIsoName() != null
                        || host.getLabel().equals(OccpParser.ROUTER_NAME)) {
                    // Again we skip clones, ISO VMs and the router
                    continue;
                }
                OccpHV hv = hvs.get(vm2hv.get(host.getLabel()));
                String exportName = host.getOvaName();
                // Don't re-export the same file
                if (scenarioBaseDir.resolve("Export/" + exportName).toFile().exists()) {
                    logger.info("Using existing file \"" + exportName + "\" for \"" + host.getLabel() + "\"");
                    continue;
                }
                if (setup.connect(hv.getName())) {
                    OccpVM vm = hv.getVM(host.getLabel());
                    try {
                        hv.exportVM(vm, scenarioName, exportName);
                        logger.info("Starting transfer of " + exportName);
                        setup.fetchFile(hv.getName(), scenarioName + "/Export/" + exportName,
                                scenarioBaseDir.resolve("Export/" + exportName).toString());
                        logger.info("Finished transfer of " + exportName);
                    } catch (OccpException e) {
                        logger.log(Level.SEVERE, e.getMessage(), e);
                        failure = true;
                        break;
                    }
                } else {
                    logger.severe("Error could not bring up setup VPN");
                    failure = true;
                    break;
                }
            }
        }

        // Create the package archive if we have collected all the ova files successfully
        if (!failure) {
            File pkgFile = scenarioBaseDir.resolve(scenarioName + ".tar").toFile();
            pkgFile.delete();
            FileOutputStream pkgOut;
            try {
                pkgOut = new FileOutputStream(pkgFile);
                TarArchiveOutputStream pkg = new TarArchiveOutputStream(pkgOut);
                Packager pkger = new Packager(pkg);
                Files.walkFileTree(scenarioBaseDir, pkger);
                pkg.close();
            } catch (IOException e) {
                logger.severe("Failed to create package: " + e.getLocalizedMessage());
                failure = true;
            }
        }
        return !failure;
    }

    /**
     * @return Success/failure
     */
    private static boolean configureRuntimeVPN() {
        boolean localFailure = false;
        hv2net_vpn = new HashMap<>();
        net2vpn = new HashMap<>();
        int vpnPort = 7890;
        for (Entry<String, Set<String>> entry : net2hv.entrySet()) {
            String network = entry.getKey();
            Set<String> nethvs = entry.getValue();
            // Sufficient condition for needing to setup a VPN
            if (nethvs.size() > 1) {
                vpnPort += 1;
                logger.fine("Network " + network + " spans " + nethvs.toString());
                boolean foundServer = false;
                for (String hv : nethvs) {
                    if (!hv2net_vpn.containsKey(hv)) {
                        hv2net_vpn.put(hv, new TreeSet<String>());
                    }
                    logger.fine("adding " + network + " to " + hv);
                    hv2net_vpn.get(hv).add(network);
                    if (hv2vpnip.containsKey(hv) && !net2vpn.containsKey(network)) {
                        logger.fine("Using VPN Server: " + hv2vpnip.get(hv));
                        CA ca = new CA();
                        // Generate one CA per VPN
                        CA.CertPair caKey = ca.generateCA();
                        net2vpn.put(network, new VpnConnection(hv2vpnip.get(hv), vpnPort, ca, caKey));
                        foundServer = true;
                    }
                }
                if (!foundServer) {
                    localFailure = true;
                    logger.severe("No IP specified for any hypervisor that " + network + " is on");
                }
            }
        }
        return localFailure;
    }

    /**
     * Rather than calling System.exit directly, calling this gives the chance for additional output
     * 
     * @param code - The code to exit
     */
    private static void exitProgram(int code) {
        logger.fine("Executed in: " + (System.currentTimeMillis() - startTime) / 1000 + " seconds");
        if (code != 0) {
            logger.severe("Please fix the above errors");
        }
        System.exit(code);
    }

    @SuppressWarnings("javadoc")
    public static void main(String[] args) {
        // Attempt to read in the logging configuration file for this program
        try {
            LogManager.getLogManager().readConfiguration(
                    new FileInputStream(System.getProperty("user.home") + "/occp/.logging.properties"));
        } catch (FileNotFoundException e) {
            // Logging properties missing, using defaults instead
        } catch (SecurityException | IOException e) {
            // We failed to read it, we can't log this
            System.err.println(e.getLocalizedMessage());
        }

        logger.fine("Admin Program Version: " + getProgramVersion());
        logger.fine("Admin VM Version: " + getAdminVMVersion());
        // Simple class initialization
        ExitCode exitCode = ExitCode.OK;

        modeList = StringUtils.join(Modes, "|");

        // Flag to indicate a failure occurred somewhere but did not cause us to quit
        failure = false;

        hvs = new HashMap<String, OccpHV>();

        // Attempt to read the configuration for this program
        if (!parseGlobalConfig(System.getProperty("user.home") + "/.occp.conf")) {
            exitProgram(ExitCode.ADMIN_CONFIG.value);
        }
        occpHiddenDirPath = FileSystems.getDefault().getPath(
                OccpAdmin.globalConfig.getProperty("occpHidden", System.getProperty("user.home") + "/occp/.occp/"));

        try {
            /*
             * @formatter:off
             * # Parse command line arguments
             * + if (mode == addhv)
             * - Save HV information, exit
             * # Parse configuration information .
             * # Check/Create necessary networks
             * # Search for existing VM instances
             * # Connect machines to networks
             * # Optionally create snapshot, if requested, else use existing
             * snapshot
             * # Build router configuration files/floppy image
             * # Build VPN configuration files
             */
            // @formatter:on

            // Handle the arguments
            String[] unhandledParameters = parseParameters(args);
            // This mode requires no other work
            if (runMode.equalsIgnoreCase("delhv")) {
                if (OccpHVFactory.removeHypervisor(hvName)) {
                    logger.info("Removed hypervisor: " + hvName);
                    exitProgram(ExitCode.OK.value);
                } else {
                    logger.severe("Failed to remove hypervisor: " + hvName);
                    exitProgram(ExitCode.HYPERVISOR_CACHE.value);
                }
            }

            // If we aren't just saving HV information, we need a valid configuration to proceed
            if (!runMode.equalsIgnoreCase("addhv") && !runMode.equalsIgnoreCase("delhv")) {
                if (ConfigFile == null) {
                    logger.severe("Configuration file required (--config)");
                    exitProgram(ExitCode.SCENARIO_CONFIG_MISSING.value);
                }

                // Determine the base directory's location for this scenario
                scenarioBaseDir = FileSystems.getDefault().getPath(ConfigFile).toAbsolutePath().getParent();
                // Determine the scenario's name
                scenarioName = scenarioBaseDir.getFileName().toString();
                if (instanceId != null) {
                    scenarioName += "-" + instanceId;
                }

                // Attempt to read the scenario file
                parser = new OccpParser();
                if (!parser.parseConfig(ConfigFile)) {
                    // There was either a problem with the file itself or the contents. The logs will indicate the
                    // problems and we cannot continue in any sensible fashion, quit.
                    exitProgram(ExitCode.SCENARIO_CONFIG_PARSE.value);
                }
                if (runMode.equals("deploy") || runMode.equals("launch")) {
                    if (!writeReports(parser.getReports())) {
                        logger.severe("Unable to write the reports");
                        exitCode = ExitCode.REPORT_WRITE_FAILURE;
                        exitProgram(exitCode.value);
                    }
                }
            }

            // Always create a map of which VMs are on which host, even if there is only one
            hv2vm = new HashMap<String, List<OccpHost>>();
            vm2hv = new HashMap<String, String>();
            // Hypervisor connection details specified on the command line
            if (hypervisor != null) {

                OccpHV hv = OccpHVFactory.getOccpHVFromArgs(hvName, hypervisor, unhandledParameters);
                // Save and quit if they are just caching HV information
                if (runMode.equalsIgnoreCase("addhv")) {
                    Map<String, String> params = hv.getSaveParameters();
                    // Add local=true or false based on the local flag
                    params.put("local", localFlag + "");

                    if (!OccpHVFactory.cacheHypervsior(hvName, params)) {
                        logger.severe("Unable to add hypervisor");
                        exitProgram(ExitCode.HYPERVISOR_CACHE.value);
                    }
                    // Hypervisor added, mission accomplished
                    logger.info("Added hypervisor: " + hvName);
                    exitProgram(ExitCode.OK.value);
                }
                hv.setLocal(localFlag);
                hvs.put(hvName, hv);
                // All machines must be on the only HV available
                hv2vm.put(hvName, new ArrayList<OccpHost>(parser.hosts.values()));
                for (OccpHost host : parser.hosts.values()) {
                    vm2hv.put(host.getLabel(), "specified");
                }
            }
            // Multiple hypervisors in use, read the map file
            else if (hvMap != null) {
                // Attempt to read the map file
                if (!parsePhysMap()) {
                    logger.severe("Please fix your map file");
                    exitProgram(ExitCode.HYPERVISOR_MAP.value);
                }
                for (String name : hv2vm.keySet()) {
                    OccpHV hv = OccpHVFactory.getOccpHVFromFile(name, args);
                    if (hv == null) {
                        logger.severe("Map contains unconfigured hypervisor: " + name);
                        exitProgram(ExitCode.HYPERVISOR_MAP.value);
                    }
                    hvs.put(name, hv);
                }
            }
            // If they are using a cached Hypervisor item, find it
            else if (hypervisor == null && hvName != null) {
                hvs.put(hvName, OccpHVFactory.getOccpHVFromFile(hvName, args));
                // All machines must be on the only HV available
                hv2vm.put(hvName, new ArrayList<OccpHost>(parser.hosts.values()));
                for (OccpHost host : parser.hosts.values()) {
                    vm2hv.put(host.getLabel(), hvName);
                }
            }

            // Assume self-signed certs are ok
            trustAllHttpsCertificates();

            // Builds mappings between HV & Network and Network & HV
            buildTopologyInformation();
            if (failure) {
                exitProgram(ExitCode.SCENARIO_CONFIG_PARSE.value);
            }

            // Attempt to connect to the hypervisor(s)
            for (Entry<String, OccpHV> e : hvs.entrySet()) {
                if (e.getValue() == null || !e.getValue().connect()) {
                    logger.severe("Unable to connect to " + e.getKey() + " hypervisor");
                    failure = true;
                    exitCode = ExitCode.HYPERVISOR_CONNECT;
                    // Ensure we reach the finally block
                    return;
                }
                logger.info("Connected to " + e.getKey());
            }

            // We need the VPN information in case we need to power everything off
            if (!configureRuntimeVPN()) {
                exitCode = ExitCode.RUNTIME_VPN;
            }

            /*
             * Always power off each of the scenario VMs, and the Runtime VPN
             * machines since we need them to be in this state to continue
             */
            for (OccpHost host : parser.getOccpHosts()) {
                OccpHV hv = hvs.get(vm2hv.get(host.getLabel()));
                try {
                    OccpVM vm = hv.getVM(host.getLabel());
                    hv.powerOffVM(vm);
                } catch (VMNotFoundException e) {
                    // That's ok
                }
            }

            for (Entry<String, Set<String>> entry : hv2net_vpn.entrySet()) {
                String aHvName = entry.getKey();
                OccpHV vpnHv = hvs.get(aHvName);
                try {
                    OccpVM vpnVm = vpnHv.getVM(OccpParser.VPN_NAME);
                    vpnHv.powerOffVM(vpnVm);
                } catch (VMNotFoundException e) {
                    // That's ok
                }
            }

            // If powering off is all we should do, then end.
            if (runMode.equals("poweroff")) {
                exitCode = ExitCode.OK;
                return;
            }

            if (runMode.equals("export")) {
                if (doExport()) {
                    exitCode = ExitCode.OK;
                } else {
                    failure = true;
                    exitCode = ExitCode.EXPORT_FAILURE;
                }
                // Ensure we reach the finally block
                return;
            }

            // Check networks first, so we can ensure they exist for the machines
            logger.info("Checking appropriate networks exist on hypervisors");
            for (Entry<String, Set<OccpNetwork>> entry : hv2net.entrySet()) {
                // record failure, but don't overwrite
                logger.finest("Checking networks on " + entry.getKey());
                failure |= !checkNetworksOnHV(hvs.get(entry.getKey()), entry.getValue());
            }

            // Plus one to use a pool thread to watch the DHCP server
            // Threads are only started as needed, but only start as many as we have
            // cores
            int poolSize = Runtime.getRuntime().availableProcessors();
            String cfgPoolSize = globalConfig.getProperty("concurrency");
            if (cfgPoolSize != null) {
                try {
                    poolSize = Integer.parseInt(cfgPoolSize);
                } catch (NumberFormatException e) {
                    logger.info("Ignoring bad concurrency setting");
                }
            }
            // Ensure there is at least 1 to make progress, and one for dhcp server
            poolSize = Math.max(2, poolSize + 1);

            exec = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize);
            if (!runMode.equals("verify")) {
                // if (hvs.size() > 1) {
                setup = new SetupNetwork(hvs);
                // TODO: Change to exceptions?
                if (!setup.createConfiguration()) {
                    logger.severe("Failed to configure setup network");
                    failure = true;
                } else if (!setup.setup()) {
                    logger.severe("Failed to setup setup network");
                    failure = true;
                }
            }

            if (!failure) {
                // Ensure that the virtual machines exist where they should
                logger.info("Beginning setup of VMs");
                for (String name : hv2vm.keySet()) {
                    // record failure, but don't overwrite
                    failure |= !checkVMsOnHV(hvs.get(name), hv2vm.get(name));
                }
            }

            if (!failure) {
                if (!hv2net_vpn.isEmpty()) {
                    logger.info("Beginning setup of runtime VPN system");
                }
                for (Entry<String, Set<String>> entry : hv2net_vpn.entrySet()) {
                    String aHvName = entry.getKey();
                    ArrayList<String> vpnNetworks = new ArrayList<>(entry.getValue());
                    if (!createVPNFloppy(aHvName, vpnNetworks)) {
                        failure = true;
                        break;
                    }
                    OccpHV vpnHv = hvs.get(aHvName);
                    OccpVM vpnVm = null;
                    try {
                        vpnVm = vpnHv.getVM(OccpParser.VPN_NAME);
                    } catch (VMNotFoundException e) {
                        // We can create this one from scratch
                        String isoPath = occpHiddenDirPath.resolve("vpn.iso").toString();
                        setup.stageFile(vpnHv.getName(), isoPath);
                        vpnVm = vpnHv.createVMwithISO(OccpParser.VPN_NAME, isoPath);
                    }
                    if (!runMode.equals("verify")) {
                        setup.stageFile(aHvName, scenarioBaseDir.resolve(aHvName + ".img").toString());
                        vpnHv.attachFloppy(vpnVm, aHvName + ".img");
                        // Make sure to ignore the first interface, should be configured by user for external
                        // connectivity
                        vpnNetworks.add(0, null);
                        vpnHv.assignVMNetworks(vpnVm, vpnNetworks);
                        vpnHv.setBootCD(vpnVm);
                    }
                    if (!failure && runMode.equals("launch")) {
                        vpnHv.powerOnVM(vpnVm);
                    }
                }
            }
            if (parser.routerNeeded) {
                OccpHV routerhv = null;
                routerhv = hvs.get(vm2hv.get(OccpParser.ROUTER_NAME));

                if (routerhv == null) {
                    logger.severe("No hypervisor specified for the router");
                    failure = true;
                } else

                // Create the router.img configuration floppy based on information in Config

                if (!failure && !runMode.equals("verify")) {
                    if (createRouterFloppy(parser.hosts.get(OccpParser.ROUTER_NAME))) {
                        logger.info("Preparing Router VM");
                        OccpVM routervm = routerhv.getVM(OccpParser.ROUTER_NAME);
                        setup.stageFile(routerhv.getName(), scenarioBaseDir.resolve("router.img").toString());
                        // Note that the "from" location depends on hypervisor
                        routerhv.attachFloppy(routervm, "router.img");
                        // CD should already be attached
                        routerhv.setBootCD(routervm);
                        logger.info("The Router VM has been prepared successfully");
                    } else {
                        failure = true;
                    }
                }
            }

            if (!failure && runMode.equals("launch")) {
                // Power on each of the scenario VMs ignoring intermediate VMs
                logger.info("Launching VMs");
                for (OccpHost host : parser.getOccpHosts()) {
                    if (host.getIntermediate()) {
                        continue;
                    }
                    OccpHV hv = hvs.get(vm2hv.get(host.getLabel()));
                    OccpVM vm = hv.getVM(host.getLabel());
                    logger.info("Powering on the VM \"" + host.getLabel() + "\" on the hypervisor \"" + hv.getName()
                            + '"');
                    hv.powerOnVM(vm);
                }
            }
        } catch (IllegalArgumentException e) {
            logger.log(Level.SEVERE, "Illegal Argument: " + e.getLocalizedMessage(), e);
            exitCode = ExitCode.ILLEGAL_ARGUMENT;
            failure = true;
            printUsage();
        } catch (OccpException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            failure = true;
            exitCode = ExitCode.GENERAL_FAILURE;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unknown error", e);
            failure = true;
            exitCode = ExitCode.GENERAL_FAILURE;
        } finally {
            try {
                // Shutdown the DHCP server and the thread pool
                if (mondhcp != null) {
                    mondhcp.stop();
                }
                if (exec != null) {
                    exec.shutdown();
                    try {
                        while (!exec.awaitTermination(10, TimeUnit.SECONDS)) {
                            logger.info("Waiting for tasks to finish (" + exec.getActiveCount() + ")");
                        }
                    } catch (InterruptedException e) {
                        logger.log(Level.WARNING, "Interrupted waiting for tasks to finish", e);
                        failure = true;
                    }
                }

                if (setup != null) {
                    setup.stop();
                }

                if (configManager != null) {
                    // A ConfigManagerControl object was used, clean up
                    configManager.cleanUp();
                }

                for (OccpHV hv : hvs.values()) {
                    hv.disconnect();
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to disconnect", e);
            }
            System.runFinalization();
            if (failure) {
                if (exitCode == ExitCode.OK) {
                    exitCode = ExitCode.GENERAL_FAILURE;
                }
            } else {
                String successMessage = runMode + " complete";
                if (regenFlag) {
                    successMessage += "d with regen";
                }
                logger.info(successMessage);
                exitCode = ExitCode.OK;
            }
            exitProgram(exitCode.value);
        }
    }
}
