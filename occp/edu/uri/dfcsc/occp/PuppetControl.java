package edu.uri.dfcsc.occp;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.uri.dfcsc.occp.exceptions.configmanager.ConfigManagerException;
import edu.uri.dfcsc.occp.exceptions.configmanager.ConfigManagerPermanentFailureException;
import edu.uri.dfcsc.occp.utils.BaseVMRemoteConfig;
import edu.uri.dfcsc.occp.utils.BaseVMRemoteConfig.CommandOutput;

/**
 * Controls Puppet as the configuration management system
 */
public class PuppetControl extends ConfigManagerControl {

    private static final String moduleDirPath = OccpAdmin.occpHiddenDirPath.resolve("modules/").toString();
    private static final String reportsSharePath = OccpAdmin.occpHiddenDirPath.resolve("OCCPReports/").toString();

    private static final Logger logger = Logger.getLogger(PuppetControl.class.getName());

    /**
     * Constructor
     * 
     * @param hosts - The list of hosts we will be responsible for
     */
    public PuppetControl(final ArrayList<OccpHost> hosts) {
        super(hosts);
    }

    @Override
    public void setup(String scenarioDirectory) throws ConfigManagerException {
        logger.fine("Setting up puppet master");

        // TODO Figure out reliable way to determine if apache is running passenger puppet master correctly

        String nodeFile = this.compileNodeFile(hosts);
        if (!this.prepareMaster(nodeFile, this.uniquePacks(hosts), scenarioDirectory)) {
            throw new ConfigManagerPermanentFailureException("Unable to setup the puppet master");
        }
    }

    @Override
    public void doPhase(String label, String phase, boolean poweroff) throws ConfigManagerException {
        // Retrieve host we are configuring
        OccpHost host = super.getHostByLabel(label);

        // Did we retrieve it successfully?
        if (host != null) {
            if (!this.cleanCert(label)) {
                throw new ConfigManagerPermanentFailureException("Cannot continue phase application due to dirty cert");
            }
            // Attempt to run puppet

            BaseVMRemoteConfig remoteConfig = new BaseVMRemoteConfig("root", host.getSetupIP(), "0ccpadmin");

            // Attempt to sync the time on the client so the certificates do not have a time issue
            CommandOutput timeSyncCommandOutput = remoteConfig.sendCommand("ntpdate -v -d 12.14.16.1");
            if (timeSyncCommandOutput.getExitStatus() != 0) {
                logger.warning("Unable to sync the time on \""
                        + label
                        + "\". This may cause issues with puppet's certificates if the master and client do not agree on the time.");
            }

            // Puppet command to run
            String puppetCommandBase = "/opt/puppetlabs/bin/puppet agent --certname " + label
                    + " --logdest console --onetime --no-daemonize --detailed-exitcodes --environment ";

            CommandOutput puppetOutput = null;
            // Attempt to run the command
            if (poweroff) {
                // Determine which poweroff environment to use
                if (phase.equals("phase2")) {
                    puppetOutput = remoteConfig.sendCommand(puppetCommandBase + phase, "/sbin/poweroff");
                } else if (phase.equals("phase1")) {
                    puppetOutput = remoteConfig
                            .sendCommand(puppetCommandBase + phase, puppetCommandBase + "poweroffp1");
                } else {
                    puppetOutput = remoteConfig.sendCommand(puppetCommandBase + phase, puppetCommandBase + "poweroff");
                }
            } else {
                puppetOutput = remoteConfig.sendCommand(puppetCommandBase + phase);
            }
            // Determine success of the command
            if (puppetOutput.getExitStatus() == 0 || puppetOutput.getExitStatus() == 2) {
                logger.fine("Puppet completed phase " + phase + " for " + label + " (Exited: "
                        + puppetOutput.getExitStatus() + ")");
                logger.finest(puppetOutput.getOutput().replaceAll("\\e\\[[01]?;?\\d?\\d?m", ""));
            } else {
                logger.severe("Puppet did not complete phase " + phase + " on \"" + label + "\" as expected"
                        + " (Exited: " + puppetOutput.getExitStatus() + ")");
                logger.severe("stdout:" + puppetOutput.getOutput().replaceAll("\\e\\[[01]?;?\\d?\\d?m", ""));
                logger.severe("stderr:" + puppetOutput.getErrorOutput().replaceAll("\\e\\[[01]?;?\\d?\\d?m", ""));
                throw new ConfigManagerPermanentFailureException("Puppet phase application failed");
            }

            // As of 6/25/2014 and puppet version 3.6.2:
            // Both puppet agent and puppet apply even with --detailed-exitcodes (implied by --test here) will
            // return zero if there was a dependency cycle. This is problematic because it will mean the manifest
            // does not apply but also doesn't inform us of the failure.
            //
            // Applicable ticket: https://tickets.puppetlabs.com/browse/PUP-1929
            //
            // This work around looks for "dependency cycle" in stderr and assumes the worst
            String puppetError = puppetOutput.getErrorOutput();
            if (puppetError.contains("dependency cycle")) {
                logger.severe("There seems to be a dependency cycle, puppet cannot apply " + phase + " on \"" + label
                        + '"');
                logger.severe(puppetError);
                throw new ConfigManagerPermanentFailureException("Puppet phase application failed");
            }

        } else {
            // Couldn't find the host
            throw new ConfigManagerPermanentFailureException("Unable to locate the host \"" + label
                    + "\" to apply phase " + phase + " to");
        }
    }

    @Override
    public void cleanUp() throws ConfigManagerException {
        logger.fine("Puppet Control is cleaning up");
        // No work needs to be done when using passengers
    }

    /**
     * Attempts to prepare the puppet master by writing the nodes.pp file. Then ensuring each specified content pack
     * exists and creates symbolic links in the module path.
     * 
     * @param compiledNodes String to write as nodes.pp
     * @param contentPacks A list of the required content packs
     * @param scenarioFileDir The path to the directory this scenario is in
     * @return Success/failure
     */
    private boolean prepareMaster(String compiledNodes, ArrayList<ContentPackInfo> contentPacks, String scenarioFileDir) {
        boolean encounteredError = false;

        // Set the Reports path
        String reportsPath = scenarioFileDir + "/Reports/";

        // 1. Check if there is a valid Reports directory
        // 2. If there is link it to the puppet share
        File reportsDirectory = new File(reportsPath), reportsShareDirectory = new File(reportsSharePath);
        if (reportsDirectory.exists()) {
            // Check that it is actually a directory
            if (reportsDirectory.isDirectory()) {
                // The source exists, unlink existing share if needed then link
                try {
                    // Attempt to unlink if needed
                    Files.deleteIfExists(reportsShareDirectory.toPath());
                    try {
                        // Attempt to create the link
                        Files.createSymbolicLink(reportsShareDirectory.toPath(), reportsDirectory.toPath());
                    } catch (IOException exception) {
                        // Unable to create the link
                        logger.severe("Unable to create link for the reports share: " + exception.getMessage());
                        encounteredError = true;
                    }
                } catch (IOException exception) {
                    // unlink was not successful
                    logger.severe("Unable to clean up \"" + reportsSharePath
                            + "\". Try manually unlinking or removing that file.");
                    encounteredError = true;
                }
            } else {
                // Reports directory was not a directory, skip the linking
                logger.warning("The reports directory \"" + reportsPath
                        + "\" wasn't a directory as expected. Will not share reports.");
            }
        } else {
            // There was no Reports directory, might be okay if no reports were needed. Regardless, skip sharing the
            // non-existent reports
            logger.warning("The reports directory was missing. Will not share reports.");
        }

        // Set the Content Pack and the Dependency paths
        String contentPacksPath = scenarioFileDir + "/ContentPacks/";
        String contentPackDependsPath = scenarioFileDir + "/PackDependencies/";

        // Prepare the module directory by:
        // Making sure it exists
        // Emptying it
        // Linking required content packs and dependencies
        logger.fine("Preparing module directory");

        File moduleDirectory = new File(moduleDirPath), dependsDirectory = new File(contentPackDependsPath);

        if (moduleDirectory.exists()) {
            // Ensure that the module directory is in fact a directory
            if (moduleDirectory.isDirectory()) {
                // Now lets ensure the directory is empty
                if (!cleanDirectoryContents(moduleDirectory.listFiles())) {
                    logger.severe("Could not empty the module directory: " + moduleDirPath);
                    encounteredError = true;
                }
            } else {
                // For some reason the module directory is not a directory we assume complete control over this so we
                // will now remedy this by blindly deleting what ever it is and creating it as we need it to be

                if (moduleDirectory.delete()) {
                    if (!moduleDirectory.mkdir()) {
                        logger.warning("The module directory was actually a file instead, we deleted the file but we failed to create the directory. We expected "
                                + moduleDirPath + " to be a directory.");
                        encounteredError = true;
                    }
                } else {
                    logger.warning("The module directory was actually a file instead. We failed to delete it. We expected "
                            + moduleDirPath + " to be a directory.");
                    encounteredError = true;
                }
            }
        } else {
            // For some reason the module directory does not exist, we'll try to fix that now
            if (!moduleDirectory.mkdir()) {
                logger.warning("Module Directory did not exist and we failed to create it. We expected "
                        + moduleDirPath + " to be a directory.");
                encounteredError = true;
            }
        }
        if (!encounteredError) {
            logger.fine("Verifying and linking content packs");

            File tempTargetFile = null, tempLinkFile = null;
            ArrayList<String> encounteredPacks = new ArrayList<>();
            if (contentPacks != null) {
                // For each content pack attempt to link
                for (ContentPackInfo pack : contentPacks) {
                    String packName = pack.getPackName();
                    if (!encounteredPacks.contains(packName)) {
                        // Was not a duplicate, we may link and record that we've encountered this pack
                        encounteredPacks.add(packName);
                        tempTargetFile = new File(contentPacksPath + packName);
                        tempLinkFile = new File(moduleDirPath + '/' + packName);
                        if (tempTargetFile.isDirectory()) {
                            // create link
                            try {
                                Files.createSymbolicLink(tempLinkFile.toPath(), tempTargetFile.toPath());
                            } catch (IOException exception) {
                                logger.log(Level.SEVERE, "Failed to make link: " + tempLinkFile.toPath(),
                                        exception.getMessage());
                                encounteredError = true;
                            }
                        } else {
                            logger.severe(tempTargetFile.toPath() + " was not found but is required");
                            encounteredError = true;
                        }
                    }
                }
            }
        }

        // Link all dependencies along side the content packs
        if (dependsDirectory.exists()) {
            if (dependsDirectory.isDirectory()) {
                File[] contents = dependsDirectory.listFiles();
                for (File file : contents) {
                    File tempLinkFile = new File(moduleDirPath + '/' + file.getName());
                    // create link
                    try {
                        Files.createSymbolicLink(tempLinkFile.toPath(), file.toPath());
                    } catch (IOException exception) {
                        logger.log(Level.SEVERE, "Failed to make link: " + tempLinkFile.toPath(),
                                exception.getMessage());
                        encounteredError = true;
                    }
                }
            }
        }

        // Write the nodes file to the manifest location
        logger.fine("Writing nodes.pp");
        File nodesFile = new File(OccpAdmin.occpHiddenDirPath.resolve("nodes.pp").toString());
        logger.fine("Node file: " + nodesFile.getAbsolutePath());
        try {
            FileWriter nodesWriter = new FileWriter(nodesFile, false);
            nodesWriter.write(compiledNodes);
            nodesWriter.close();
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Unable to write nodes file", exception);
            encounteredError = true;
        }

        // Everything has gone well so far, we can make a copy of the nodes file we wrote before as "lastrun.pp"
        if (!encounteredError) {
            logger.fine("Writing lastrun.pp");
            File lastrunFile = new File(scenarioFileDir + "/lastrun.pp");
            try {
                FileWriter lastrunWriter = new FileWriter(lastrunFile, false);
                lastrunWriter.write(compiledNodes);
                lastrunWriter.close();
            } catch (IOException exception) {
                logger.log(Level.SEVERE, "Unable to write lastrun file", exception);
                encounteredError = true;
            }
        }
        return !encounteredError;
    }

    private boolean cleanDirectoryContents(File[] contents) {
        boolean encounteredError = false;
        if (contents != null) {
            for (File file : contents) {
                if (Files.isSymbolicLink(file.toPath())) {
                    // Using Files.delete() will unlink instead of following and deleting actual content
                    try {
                        Files.delete(file.toPath());
                    } catch (IOException e) {
                        encounteredError = true;
                    }
                } else {
                    // If a directory, delete it's contents first then it
                    if (file.isDirectory()) {
                        // Delete folder's contents
                        if (!cleanDirectoryContents(file.listFiles())) {
                            encounteredError = true;
                        }
                    }
                    // Delete either the now empty directory or the regular file it was
                    if (!file.delete()) {
                        encounteredError = true;
                    }
                }
            }
        }
        return !encounteredError;
    }

    /**
     * Used to compile the node definitions for each host in to one string.
     * Ignores the Router.
     * 
     * @param hosts a list of OccpHosts to consider
     * @return A string that could be written as nodes.pp representing the given
     *         hosts
     */
    private String compileNodeFile(ArrayList<OccpHost> hosts) {
        StringBuilder nodesContent = new StringBuilder();

        nodesContent.append("# OCCP auto generated nodes file\n\n");

        for (OccpHost host : hosts) {
            String hostLabel = host.getLabel();
            // Do not consider the Router
            if (!hostLabel.equalsIgnoreCase("router")) {
                nodesContent.append(this.generatePuppetNode(host));
                nodesContent.append("\n");
            }
        }
        return nodesContent.toString();
    }

    /**
     * Generate the node definition using the label for the node name
     * 
     * @param host - Host to generate puppet manifest content for
     * @return String containing a node definition for this host
     */
    private String generatePuppetNode(OccpHost host) {
        // Eventual Node String
        StringBuilder result = new StringBuilder();
        result.append("node '" + host.getLabel() + "' {\n");

        // Formatting helper
        int currentTabLevel = 1;

        // Generate OCCP variables
        result.append(this.generateVariables(host, this.puppetTab(currentTabLevel)));
        // Newline after variables
        result.append("\n");

        // Hostname class
        result.append(puppetTab(currentTabLevel));
        result.append("class { 'occphostname':");
        if (host.getDomain() != null) {
            // Both hostname and domain are available for FQDN
            result.append("\n");
            result.append(puppetTab(++currentTabLevel));
            result.append("hostname => '");
            result.append(host.getHostname());
            result.append("',\n");
            result.append(puppetTab(currentTabLevel));
            result.append("domain   => '");
            result.append(host.getDomain());
            result.append("',\n");
            result.append(puppetTab(--currentTabLevel));
            result.append("}\n");
        } else {
            // Only the host name is availible
            result.append(" hostname => '");
            result.append(host.getHostname());
            result.append("' }\n");
        }
        result.append("\n"); // Newline after hostname

        // encapsulate any phase specific stuff
        result.append(puppetTab(currentTabLevel++));
        result.append("if $environment == 'phase1' {\n");
        // Currently nothing needed for phase 1
        result.append(puppetTab(--currentTabLevel));
        result.append("} elsif $environment == 'phase2' {\n");
        currentTabLevel++;

        // Generate network config if we are responsible for it
        if (host.getInterfaceConfig()) {
            // Add any interface configs
            result.append(generateInterfaceConfig(host.getInterfaces(), currentTabLevel));
        }

        result.append(puppetTab(--currentTabLevel));
        result.append("}\n"); // Close phase2 if statement

        for (ContentPackInfo pack : host.getContentPacks()) {
            // Depending on # of Custom Parameters
            // 0 - Simple include one liner
            // 1 - One line class statement
            // >1 - Multiline class with alignment of '=>'

            int numberOfCustomParameters = pack.getNumberOfCustomParameters();

            if (numberOfCustomParameters == 0) {
                // There are no parameters, a simple include statement will work here
                result.append(this.puppetTab(currentTabLevel));
                result.append("class { '");
                result.append(pack.getPackName());
                if (pack.getClassName() != null) {
                    result.append("::");
                    result.append(pack.getClassName());
                }
                result.append("': }\n");
            } else if (numberOfCustomParameters == 1) {
                result.append(this.puppetTab(currentTabLevel));
                result.append("class { '");
                result.append(pack.getPackName());
                if (pack.getClassName() != null) {
                    result.append("::");
                    result.append(pack.getClassName());
                }
                result.append("': ");

                // Unfortunately it is hard to pull an unknown parameter and value even when we know there is only one
                Hashtable<String, String> customParameters = pack.getCustomParameters();
                Enumeration<String> parameters = customParameters.keys();
                String parameter = parameters.nextElement();
                result.append(parameter + " => " + customParameters.get(parameter) + " }\n");

            } else if (numberOfCustomParameters > 1) {
                // Open class
                result.append(this.puppetTab(currentTabLevel));
                result.append("class { '");
                result.append(pack.getPackName());
                if (pack.getClassName() != null) {
                    result.append("::");
                    result.append(pack.getClassName());
                }
                result.append("':\n");

                // Place parameters
                Hashtable<String, String> customParameters = pack.getCustomParameters();
                Enumeration<String> parameters = customParameters.keys();
                int longestParameterName = pack.getLongestParameterNameLength();

                while (parameters.hasMoreElements()) {
                    currentTabLevel++;
                    String parameter = parameters.nextElement();
                    int parameterLength = parameter.length();
                    int spacesNeeded = longestParameterName - parameterLength;
                    String value = customParameters.get(parameter);
                    result.append(this.puppetTab(currentTabLevel));
                    result.append(parameter);
                    // space the '=>' properly to conform to style guides
                    result.append(this.generateSpace(spacesNeeded));
                    result.append(" => " + value + ",\n");
                    currentTabLevel--;
                }

                // Close class
                result.append(this.puppetTab(currentTabLevel));
                result.append("}\n");
            }
        }

        // Include the poweroff mechanism
        result.append(puppetTab(currentTabLevel++));
        result.append("if $environment == 'poweroffp1' {\n");
        result.append(puppetTab(currentTabLevel));
        result.append("class { occp::poweroff: cleanup => 'phase1' }\n");
        result.append(puppetTab(--currentTabLevel));
        result.append("} elsif $environment == 'poweroffp2' {\n");
        result.append(puppetTab(++currentTabLevel));
        result.append("class { occp::poweroff: cleanup => 'phase2' }\n");
        result.append(puppetTab(--currentTabLevel));
        result.append("} elsif $environment == 'poweroff' {\n");
        result.append(puppetTab(++currentTabLevel));
        result.append("include occp::poweroff\n");
        result.append(puppetTab(--currentTabLevel));
        result.append("}\n");

        // Close node definition, we assume anything before this has properly added a newline after itself
        result.append("}");

        return result.toString();
    }

    /**
     * Generate the OCCP puppet variables for this host with no indentation
     * 
     * @param host - host to generate variables for
     * @return A string with the variables without trailing newline character
     */
    @SuppressWarnings("unused")
    private String generateVariables(final OccpHost host) {
        return this.generateVariables(host, this.puppetTab(0));
    }

    /**
     * Generate the OCCP puppet variables for this host with given indentation
     * 
     * @param host - host to generate variables for
     * @param currentTabLevel a string with the number of spaces to prefix each
     *            line with
     * @return A string with the variables without trailing newline character
     */
    private String generateVariables(OccpHost host, final String currentTabLevel) {
        StringBuilder occpVariables = new StringBuilder();
        occpVariables.append(currentTabLevel + "# AdminVM Generated OCCP Variables\n");
        if (host.getHostname() != null) {
            occpVariables.append(currentTabLevel + "$occp_hostname = '" + host.getHostname() + "'\n");
        }

        if (host.getDomain() != null) {
            occpVariables.append(currentTabLevel + "$occp_domain = '" + host.getDomain() + "'\n");
        }

        if (host.getHostname() != null && host.getDomain() != null) {
            occpVariables.append(currentTabLevel + "$occp_fqdn = '" + host.getHostname() + '.' + host.getDomain()
                    + "'\n");
        }

        int numberOfInterfaces = host.getInterfaces().size();
        occpVariables.append(currentTabLevel + "$occp_numberOfInterfaces = " + numberOfInterfaces + "\n");

        StringBuilder interfaceNames = new StringBuilder(), interfaceIPv4s = new StringBuilder();
        interfaceNames.append('[');
        interfaceIPv4s.append('[');

        String arraySeparator = ", ", arrayTerminator = "]\n";
        for (int i = 0; i < numberOfInterfaces; i++) {
            OccpNetworkInterface currentInterface = host.getInterfaces().get(i);
            interfaceNames.append("'" + currentInterface.getName() + "'");

            if (currentInterface.hasV4Address()) {
                interfaceIPv4s.append("'" + currentInterface.getV4Address() + "'");
            } else {
                interfaceIPv4s.append("'undetermined'");
            }

            // Add separator when we have more elements
            if (i < numberOfInterfaces - 1) {
                interfaceNames.append(arraySeparator);
                interfaceIPv4s.append(arraySeparator);
            }
        }

        interfaceNames.append(arrayTerminator);
        interfaceIPv4s.append(arrayTerminator);

        occpVariables.append(currentTabLevel + "$occp_InterfaceNames = " + interfaceNames);
        occpVariables.append(currentTabLevel + "$occp_InterfaceIPv4s = " + interfaceIPv4s);

        return occpVariables.toString();
    }

    /**
     * Generate a string with the amount of tabs specified, adhering to the
     * puppet style guide i.e. 1 tab = 2 spaces
     * 
     * @param tabs A positive int describing the number of tabs you want
     * @return A string contains the requested number of tabs or an empty string
     *         for nonsensical input
     */
    private String puppetTab(int tabs) {
        String result = ""; // Empty string for nonsensical tabs input

        if (tabs > 0) {
            // Generate the number of tabs requested
            result = generateSpace(2 * tabs);
        }
        return result;
    }

    /**
     * Creates a string with the specified number of spaces.
     * 
     * @param spaces A positive int describing the number of spaces you want.
     * @return A string containing the number of spaces or empty string for
     *         nonsensical input
     */
    private String generateSpace(int spaces) {
        if (spaces > 0) {
            StringBuilder result = new StringBuilder(spaces);

            for (int i = 1; i <= spaces; i++) {
                // Build up the number of requested spaces
                result.append(' ');
            }

            return result.toString();
        }
        return "";
    }

    /**
     * Clean the cert for the given host
     * 
     * @param certname the cert to remove
     * @return true if successful false otherwise
     */
    private boolean cleanCert(String certname) {
        String[] cmd = {"sudo", "puppet", "cert", "clean", certname };
        boolean successful = false;
        Runtime rt = Runtime.getRuntime();
        try {
            int retVal = rt.exec(cmd).waitFor();
            if (retVal == 0 || retVal == 24) {
                successful = true;
            } else {
                logger.severe("Unable to clean the puppet master certs, exited with " + retVal);
                successful = false; // Redundant but explicit
            }
        } catch (IOException | InterruptedException exception) {
            successful = false; // Redundant but explicit
            logger.log(Level.SEVERE, "Unable to clean the puppet master certs", exception);
        }
        return successful;
    }

    /**
     * Find all used content packs recording only the first if there are
     * duplicates
     * 
     * @param hosts The list of OccpHost objects to find packs for
     * @return List of ContentPackInfo objects being used with no duplicates
     */
    private ArrayList<ContentPackInfo> uniquePacks(ArrayList<OccpHost> hosts) {
        ArrayList<ContentPackInfo> result = new ArrayList<>();
        ArrayList<ContentPackInfo> temp;
        for (OccpHost host : hosts) {
            temp = host.getContentPacks();
            for (ContentPackInfo pack : temp) {
                if (!result.contains(pack)) {
                    // Pack was unique, add it
                    result.add(pack);
                }
            }
        }
        return result;
    }

    /**
     * Generate content for a manifest file to represent the networking interfaces
     * 
     * @param interfaces - The list of interfaces to generate
     * @param currentTabLevel - formatting helper gives current indentation level
     * @return A string representing the interfaces for use in a manifest
     */
    private String generateInterfaceConfig(ArrayList<OccpNetworkInterface> interfaces, int currentTabLevel) {
        StringBuilder result = new StringBuilder();

        // Consider each interface we were given
        for (OccpNetworkInterface currentInterface : interfaces) {
            // Determine if DHCP or Static
            if (currentInterface.getConfig() == OccpNetworkInterface.Config.DHCP) {

                // Open network::interface
                result.append(puppetTab(currentTabLevel++));
                result.append("network::interface { '" + currentInterface.getName() + "':\n");

                // Parameter
                result.append(puppetTab(currentTabLevel));
                result.append("enable_dhcp => true,\n");

                // Close network::interface
                result.append(puppetTab(--currentTabLevel));
                result.append("}\n");
            } else if (currentInterface.getConfig() == OccpNetworkInterface.Config.STATIC) {

                // The longest parameter is ipaddress, broadcast is also the same length but optional. This will be used
                // to determine the spacing required for formatting of the => symbols
                final int longestParam = 9;

                // Open network::interface
                result.append(puppetTab(currentTabLevel++));
                result.append("network::interface { '" + currentInterface.getName() + "':\n");

                // Parameter: ipaddress
                result.append(puppetTab(currentTabLevel));
                result.append("ipaddress");
                result.append(generateSpace(longestParam - 9));
                result.append(" => '");
                result.append(currentInterface.getV4Address());
                result.append("',\n");
                // Parameter: netmask
                result.append(puppetTab(currentTabLevel));
                result.append("netmask");
                result.append(generateSpace(longestParam - 7));
                result.append(" => '");
                result.append(currentInterface.getV4Netmask());
                result.append("',\n");

                if (currentInterface.hasV4Broadcast()) {
                    // Parameter: broadcast
                    result.append(puppetTab(currentTabLevel));
                    result.append("broadcast");
                    result.append(generateSpace(longestParam - 9));
                    result.append(" => '");
                    result.append(currentInterface.getV4Broadcast());
                    result.append("',\n");
                }
                if (currentInterface.hasV4Gateway()) {
                    // Parameter: gateway
                    result.append(puppetTab(currentTabLevel));
                    result.append("gateway");
                    result.append(generateSpace(longestParam - 7));
                    result.append(" => '");
                    result.append(currentInterface.getV4Gateway());
                    result.append("',\n");
                }
                // Close network::interface
                result.append(puppetTab(--currentTabLevel));
                result.append("}\n");
            }
        }
        return result.toString();
    }
}
