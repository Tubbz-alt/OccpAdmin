package edu.uri.dfcsc.occp;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import org.apache.commons.lang.StringUtils;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import edu.uri.dfcsc.occp.exceptions.parsing.MismatchedExpectedType;
import edu.uri.dfcsc.occp.exceptions.parsing.MissingRequired;
import edu.uri.dfcsc.occp.exceptions.parsing.XMLParseException;
import edu.uri.dfcsc.occp.generator.Generator;
import edu.uri.dfcsc.occp.generator.Generator.InvalidGeneratorParameterException;
import edu.uri.dfcsc.occp.generator.GeneratorFactory;
import edu.uri.dfcsc.occp.generator.GeneratorFactory.UnknownGeneratorException;

/**
 * Reads an OCCP scenario configuration file and extracts the information required by this admin program and the Game
 * Server
 */
public class OccpParser {
    /**
     * Reserved name for the OCCP router VM
     */
    public static final String ROUTER_NAME = "Router";
    /**
     * Reserved name for the OCCP Setup VPN VM
     */
    public static final String SETUPVPN_NAME = "SetupVPN";
    /**
     * Reserved name for the OCCP Runtime VPN VM
     */
    public static final String VPN_NAME = "RuntimeVPN";
    /**
     * Reserved name for the OCCP Game Server VM
     */
    public static final String GAMESERVER_NAME = "gameserver";
    /**
     * List of all OCCP reserved VM names
     */
    public static final String[] RESERVED_HOST_NAMES = { ROUTER_NAME, SETUPVPN_NAME, VPN_NAME };
    /**
     * Name of the network that will be considered the "internet" inside the scenario
     */
    public static final String INTERNET_NAME = "fake-internet";

    private static Logger logger = Logger.getLogger(OccpParser.class.getName());

    /**
     * List of hosts in the Scenario File
     */
    public Map<String, OccpHost> hosts;

    /**
     * List of networks in the Scenario file
     */
    public Map<String, OccpNetwork> networks;

    /**
     * List of DNS OccpDNSEntry objects
     */
    public List<OccpDNSEntry> dns;

    private NamedNodeMap nnm; // cache

    private Map<String, String> reports = null;
    boolean routerNeeded = false;

    private OccpVariables occpVariables = null;

    // Getters
    /**
     * Get a list of the OccpHost objects.
     * 
     * @return ArrayList of OccpHosts
     */
    public ArrayList<OccpHost> getOccpHosts() {
        ArrayList<OccpHost> result = new ArrayList<OccpHost>();
        Set<String> labels = this.hosts.keySet();
        for (String label : labels) {
            result.add(this.hosts.get(label));
        }
        return result;
    }

    /**
     * Reads in the specified XML file and parses it for information related to
     * the configuration of OCCP hosts
     * 
     * @param configFile path to the XML file to parse
     * @return Whether or not the parse was successful. True meant no detectable
     *         errors encountered
     */
    public boolean parseConfig(String configFile) {
        boolean parseErrorEncountered = false;
        boolean usingInstance = false;
        reports = new HashMap<>();
        try {
            String instanceFileName = "instance.xml";
            if (OccpAdmin.instanceId != null) {
                instanceFileName = "instance-" + OccpAdmin.instanceId + ".xml";
            }
            String instanceXMLFilePath = OccpAdmin.scenarioBaseDir.resolve(instanceFileName).toString();
            File instanceXMLFile = new File(instanceXMLFilePath);
            File configXMLFile = new File(configFile);
            // Only use the instance.xml file if regen wasn't requested and the scenario file hasn't changed
            if (!OccpAdmin.getRegenFlag() && instanceXMLFile.exists()) {
                if (instanceXMLFile.lastModified() >= configXMLFile.lastModified()) {
                    usingInstance = true;
                    configFile = instanceXMLFilePath;
                } else {
                    OccpAdmin.setRegenFlag(true);
                    logger.info("Scenario file is newer than instance, regen mode was not requested but is now being forced");
                }
            }

            // Must use TreeMap to ensure clone ordering
            hosts = new TreeMap<String, OccpHost>();
            networks = new HashMap<String, OccpNetwork>();
            occpVariables = new OccpVariables();

            DocumentBuilderFactory dBF = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = dBF.newDocumentBuilder();
            Document doc = docBuilder.parse(configFile);

            logger.info("Reading the scenario configuration file");

            // Variable Tags
            NodeList variableTags = doc.getElementsByTagName("var");
            for (int i = 0; i < variableTags.getLength(); i++) {
                // For each tag, attempt to parse it
                if (!parseVariable(variableTags.item(i))) {
                    // Something did not go well
                    parseErrorEncountered = true;
                }
            }

            // Report Tags
            NodeList reportTags = doc.getElementsByTagName("report");
            for (int i = 0; i < reportTags.getLength(); i++) {
                // For each tag, attempt to parse it
                if (!parseReport(reportTags.item(i))) {
                    // Something did not go well
                    parseErrorEncountered = true;
                }
            }

            // Host Tags
            NodeList hostTags = doc.getElementsByTagName("host");
            if (hostTags.getLength() == 0) {
                parseErrorEncountered = true;
                logger.severe("At least one host must be defined");
            }
            for (int index = 0; index < hostTags.getLength(); ++index) {
                if (!parseHost(hostTags.item(index), index)) {
                    parseErrorEncountered = true;
                }
            }

            // Check if routing is necessary
            for (OccpHost host : hosts.values()) {
                for (OccpNetworkInterface intf : host.getInterfaces()) {
                    if (intf.getNetwork().equals(OccpParser.INTERNET_NAME)) {
                        routerNeeded = true;
                    }
                }
            }

            if (routerNeeded) {
                OccpHost routerVM = new OccpHost(ROUTER_NAME, "", "");
                routerVM.setIsoName(OccpAdmin.occpHiddenDirPath.resolve("router.iso").toString());
                hosts.put(ROUTER_NAME, routerVM);
                OccpHost host = hosts.get(GAMESERVER_NAME);
                if (host != null) {
                    OccpNetworkInterface gsLink = null;
                    for (OccpNetworkInterface link : host.getInterfaces()) {
                        if (link.getNetwork().equals(OccpParser.INTERNET_NAME)) {
                            gsLink = link;
                            gsLink.setAuto(true);
                            gsLink.setNetwork("rtr-gs-link");
                            break;
                        }
                    }
                }
            }

            // Network tags
            NodeList networkTags = doc.getElementsByTagName("network");
            for (int index = 0; index < networkTags.getLength(); ++index) {
                if (!parseNetwork(networkTags.item(index), index)) {
                    parseErrorEncountered = true;
                }
            }

            // DNS tags
            NodeList rootdns = doc.getElementsByTagName("rootdns");
            this.dns = new ArrayList<OccpDNSEntry>();
            if (rootdns.getLength() == 1) {
                NodeList dnsEntries = (rootdns.item(0)).getChildNodes();
                for (int index = 0; index < dnsEntries.getLength(); ++index) {
                    Node entry = dnsEntries.item(index);
                    if (entry.getNodeName().equals("entry")) {
                        OccpDNSEntry item = new OccpDNSEntry(safeGetStringAttribute(entry, "name"),
                                safeGetIntegerAttribute(entry, "ttl", 1440), safeGetStringAttribute(entry, "class",
                                        "IN"), safeGetStringAttribute(entry, "rrtype"), safeGetStringAttribute(entry,
                                        "value"));
                        this.dns.add(item);
                    }
                }
            }
            if (this.occpVariables.aLookupFailed()) {
                parseErrorEncountered = true;
            }
            // Get the top level tag
            NodeList scenarioTags = doc.getElementsByTagName("occpchallenge");
            if (scenarioTags.getLength() != 1) {
                logger.severe("No scenario definition");
                parseErrorEncountered = true;
            } else if (!parseErrorEncountered) {
                Node scenario = scenarioTags.item(0);
                NodeList secondLevel = scenario.getChildNodes();
                // Remove the variables so we don't regenerate them next time
                for (int nodeNumber = 0; nodeNumber < secondLevel.getLength(); ++nodeNumber) {
                    Node candidate = secondLevel.item(nodeNumber);
                    if (candidate.getNodeName().equals("var")) {
                        scenario.removeChild(candidate);
                    }
                }
                StringWriter writer = new StringWriter();
                Transformer transformer;
                try {
                    TransformerFactory factory = TransformerFactory.newInstance();
                    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    // factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                    transformer = factory.newTransformer();
                    scenario.normalize();
                    transformer.transform(new DOMSource(scenario), new StreamResult(writer));
                } catch (TransformerFactoryConfigurationError | TransformerException e) {
                    logger.log(Level.SEVERE, "Unable to generate scenario XML for gameserver", e);
                }
                String instanceXML = getValue(writer.toString());
                if (this.occpVariables.aLookupFailed()) {
                    parseErrorEncountered = true;
                } else {
                    try {
                        if (hosts.get(GAMESERVER_NAME) != null) {
                            // Write two copies, one for the game server, and one for us
                            File gameserverXMLFile = new File(OccpAdmin.scenarioBaseDir.resolve(
                                    "ContentPacks/gameserver/files/instance.xml").toString());
                            PrintStream gameXML = new PrintStream(new FileOutputStream(gameserverXMLFile));
                            gameXML.print(instanceXML);
                            gameXML.close();
                        } else {
                            logger.warning("Your configuration does not specify the configuration of the \""
                                    + GAMESERVER_NAME + "\" VM");
                        }
                        if (!usingInstance) {
                            PrintStream instanceXMLStream = new PrintStream(new FileOutputStream(instanceXMLFile));
                            instanceXMLStream.print(instanceXML);
                            instanceXMLStream.close();
                        }
                    } catch (IOException e) {
                        logger.log(Level.SEVERE, "Error writing gameserver configuration file", e);
                        parseErrorEncountered = true;
                    }
                }
            }

        } catch (SAXException | IOException | ParserConfigurationException | MissingRequired | MismatchedExpectedType e) {
            parseErrorEncountered = true;
            logger.log(Level.SEVERE, "Error parsing the configuration file", e);
        }
        logger.info("Finished reading the scenario configuration file");
        return !parseErrorEncountered;
    }

    /**
     * Return the list of reports
     * 
     * @return the list of reports
     */
    public HashMap<String, String> getReports() {
        return (HashMap<String, String>) this.reports;
    }

    private boolean parseReport(Node reportNode) {
        boolean parseErrorEncountered = false;

        NodeList children = reportNode.getChildNodes();

        try {
            String reportName = safeGetStringAttribute(reportNode, "name");
            if (!reports.containsKey(reportName)) {
                if (children.getLength() == 1) {
                    reports.put(reportName, getValue(children.item(0).getNodeValue()));
                } else {
                    logger.warning("A report tag expects data");
                }
            } else {
                parseErrorEncountered = true;
                logger.severe("Two reports have the same name of \"" + reportName + "\"");
            }
        } catch (MissingRequired e) {
            parseErrorEncountered = true;
            logger.severe("Report tags require name attributes");
        }

        return !parseErrorEncountered;
    }

    /**
     * Parse an OCCP variable tag. See the wiki for full documentation of their usage and structure
     * 
     * @param variableNode - DOM Node for a variable
     * @return True if no parse errors encountered, false otherwise
     */
    private boolean parseVariable(Node variableNode) {
        boolean parseErrorEncountered = false;
        String variableName = null, value = null;

        try {
            // Determine variable name
            variableName = safeGetStringAttribute(variableNode, "name");

            // Get generator name if specified
            String generatorName = safeGetStringAttribute(variableNode, "generator", "");

            // Get type if specified
            String variableType = safeGetStringAttribute(variableNode, "type", "data");

            // Caches to be used later
            NodeList children = variableNode.getChildNodes();
            Node childOfInterest = null;

            // Decide if this is a normal variable or a generator
            if (generatorName != "") {
                // This is a generator so we'll need to get any params, do the generation, and append the results to our
                // variables
                GeneratorFactory generatorFactory = new GeneratorFactory();

                try {
                    Generator generator = generatorFactory.getGenerator(generatorName);
                    HashMap<String, String> parameters = new HashMap<>();
                    Node child = null;
                    // Collect any and all param tags for this
                    for (int i = 0; i < children.getLength(); i++) {
                        // Look at each child tag and determine if param tag
                        child = children.item(i);
                        if (child.getNodeName().equals("param")) {
                            // Because it is a param tag it must have a name and a value
                            try {
                                String paramName = safeGetStringAttribute(child, "name");
                                String paramValue = null;

                                // Our value should be in the first child
                                childOfInterest = child.getChildNodes().item(0);
                                // Ensure the type of the child is what we expect
                                if (childOfInterest.getNodeType() == Node.CDATA_SECTION_NODE
                                        || childOfInterest.getNodeType() == Node.TEXT_NODE) {
                                    // Expected child type found, extract value
                                    // Call getValue to allow variables to reference other variables
                                    paramValue = getValue(childOfInterest.getNodeValue());
                                } else {
                                    // Unexpected child type
                                    parseErrorEncountered = true;
                                    logger.severe("Generator param tag for \"" + variableName
                                            + "\" was specified with unexpected value type. Expecting value or CDATA.");
                                }
                                // Add to our parameters if a value was found
                                if (paramValue != null) {
                                    parameters.put(paramName, paramValue);
                                }
                            } catch (MissingRequired e) {
                                logger.severe("All generator params must have a name attribute specified.");
                                parseErrorEncountered = true;
                            }
                        }
                    }
                    if (!parseErrorEncountered) {
                        // Generate our variables
                        OccpVariables generatedVariables;
                        try {
                            generatedVariables = generator.generate(variableName, parameters);
                            // Attempt to merge the generated variables to our current variables
                            if (!this.occpVariables.mergeVariables(generatedVariables)) {
                                parseErrorEncountered = true;
                            }
                        } catch (InvalidGeneratorParameterException e) {
                            parseErrorEncountered = true;
                            logger.log(Level.SEVERE, "Failed to generate requested variable " + variableName, e);
                        }
                    }
                } catch (UnknownGeneratorException e) {
                    parseErrorEncountered = true;
                    logger.log(Level.SEVERE, "Unknown Generator for variable \"" + variableName + "\"", e);
                }
            } else {
                // This is not a generator variable, check the type
                if (variableType.equalsIgnoreCase("data")) {
                    // This is a normal variable so there should be exactly one child and it should be data.
                    // Specifically the value of the variable.
                    if (children.getLength() == 1) {
                        childOfInterest = children.item(0);
                        if (childOfInterest.getNodeType() == Node.CDATA_SECTION_NODE
                                || childOfInterest.getNodeType() == Node.TEXT_NODE) {
                            // Expected child type found, extract value
                            value = getValue(childOfInterest.getNodeValue());
                        } else {
                            parseErrorEncountered = true;
                            logger.severe("Variable tag with \"" + variableName
                                    + "\" was specified with unexpected value type. Expecting value or CDATA.");
                        }
                    } else if (children.getLength() > 1) {
                        parseErrorEncountered = true;
                        logger.severe("Variable tag with \"" + variableName
                                + "\" was specified with too many child tags. Expecting value or CDATA.");
                    } else {
                        parseErrorEncountered = true;
                        logger.severe("Variable tag with \"" + variableName + "\" was specified without a value.");
                    }
                    if (!parseErrorEncountered) {
                        // Try and set the variable
                        if (!this.occpVariables.setVariable(variableName, value, true)) {
                            // We were unable to set this variable, likely due to naming conflict
                            parseErrorEncountered = true;
                        }
                    }
                } else if (variableType.equalsIgnoreCase("array")) {
                    // Check for elements
                    Node child = null;
                    ArrayList<String> arrayElements = new ArrayList<>();
                    for (int i = 0; i < children.getLength(); i++) {
                        // Look at each child tag and determine if element tag
                        child = children.item(i);
                        if (child.getNodeName().equals("element")) {
                            // Our value should be in the first child
                            childOfInterest = child.getChildNodes().item(0);
                            // Ensure the type of the child is what we expect
                            if (childOfInterest.getNodeType() == Node.CDATA_SECTION_NODE
                                    || childOfInterest.getNodeType() == Node.TEXT_NODE) {
                                // Expected child type found, extract value. Call getValue to allow variables to
                                // reference other variables
                                arrayElements.add(getValue(childOfInterest.getNodeValue()));
                            } else {
                                // Unexpected child type
                                parseErrorEncountered = true;
                                logger.severe("var tag for \"" + variableName
                                        + "\" was specified with unexpected element value. Expecting value or CDATA.");
                            }
                        }
                    }
                    if (!parseErrorEncountered) {
                        this.occpVariables.setVariable(variableName, arrayElements, true);
                    }
                } else {
                    parseErrorEncountered = true;
                    logger.severe("Variable tag with name \"" + variableName + "\" has unknown type: \"" + variableType
                            + '"');
                }
            }
        } catch (MissingRequired e) {
            logger.severe("All OCCP variables must have a name attribute specified.");
            parseErrorEncountered = true;
        }
        return !parseErrorEncountered;
    }

    /**
     * Parse an OCCP/network tag. See the wiki for a full definition of
     * attributes and substructure. The index is used to generate a generic
     * label if it isn't specified or it meant to be a duplicate, but we need to
     * differentiate.
     * 
     * @param network - DOM Node for a network
     * @param index - unique number (small) for this network
     * @return True if no errors encountered, false otherwise
     */
    private boolean parseNetwork(Node network, int index) {
        boolean parseErrorEncountered = false;
        OccpNetwork net = new OccpNetwork();
        try {
            net.setLabel(safeGetStringAttribute(network, "label"));
            if (net.getLabel().length() > 32) {
                parseErrorEncountered = true;
                String append = "";
                if (OccpAdmin.instanceId != null && OccpAdmin.instanceId.length() > 1) {
                    append = ", try shortening the instance id";
                }
                logger.severe("network name longer than 32 characters: " + net.getLabel() + append);
            }
        } catch (MissingRequired e) {
            parseErrorEncountered = true;
            logger.log(Level.SEVERE, "Expected value missing", e);
        }
        networks.put(net.getLabel(), net);
        return !parseErrorEncountered;
    }

    /**
     * Parse a pack tag
     * 
     * @param host OccpHost the tag belongs to
     * @param node the pack element
     * @return True if parsed with no errors, false otherwise
     */
    private boolean parsePack(OccpHost host, Node node) {
        boolean parseErrorEncountered = false;

        ContentPackInfo contentPack;

        String packName = "", className = null;

        // Look for required name attribute
        try {
            packName = safeGetStringAttribute(node, "name");
        } catch (MissingRequired exception) {
            logger.log(Level.SEVERE, "Content pack for host " + host.getLabel() + " had errors" + exception);
            parseErrorEncountered = true;
        }

        // Look for optional config attribute, pack name default
        try {
            className = safeGetStringAttribute(node, "config", null);
        } catch (MissingRequired e) {
            parseErrorEncountered = true;
        }

        contentPack = new ContentPackInfo(packName, className);

        // Look for parameters add add them
        NodeList packChildren = node.getChildNodes();

        Node currentChild = null;
        for (int i = 0; i < packChildren.getLength(); i++) {
            currentChild = packChildren.item(i);

            if (currentChild.getNodeType() == Node.ELEMENT_NODE) {
                String paramName = getValue(currentChild.getNodeName());
                NodeList paramChildren = currentChild.getChildNodes();
                if (paramChildren.getLength() == 1) {
                    Node paramChild = paramChildren.item(0);
                    if (paramChild.getNodeType() == Node.CDATA_SECTION_NODE
                            || paramChild.getNodeType() == Node.TEXT_NODE) {
                        // Expected child type found, extract value
                        String paramValue = getValue(paramChild.getNodeValue());
                        contentPack.addCustomParameter(paramName, paramValue);
                    } else {
                        parseErrorEncountered = true;
                        logger.severe("Unexpected value for pack parameter \"" + paramName + "\" for content pack \""
                                + packName + "\" on host " + host.getLabel());
                    }
                } else if (paramChildren.getLength() > 1) {
                    parseErrorEncountered = true;
                    logger.severe("Unexpected tags for pack parameter \"" + paramName + "\" for content pack \""
                            + packName + "\" on host " + host.getLabel());
                } else {
                    parseErrorEncountered = true;
                    logger.severe("Expected a value for pack parameter \"" + paramName + "\" for content pack \""
                            + packName + "\" on host " + host.getLabel());
                }
            }
        }

        host.addContentPack(contentPack);

        return !parseErrorEncountered;
    }

    /**
     * Parse a content tag
     * 
     * @param host OccpHost the tag belongs to
     * @param node the content element
     * @return True if parsed with no errors, false otherwise
     */
    private boolean parseContent(OccpHost host, Node node) {
        boolean parseErrorEncountered = false;

        // Additional data for this host
        NodeList children = node.getChildNodes();

        // Look through each child
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);

            // Determine if pack
            if (child.getNodeName().equalsIgnoreCase("pack")) {
                if (!this.parsePack(host, child)) {
                    parseErrorEncountered = true;
                }
            }
            // else, we don't care. Ignore the tag
        }

        return !parseErrorEncountered;
    }

    /**
     * Parse an OCCP/host/ip tag
     * 
     * @param curHost - Host entry we are parsing
     * @param node - DOM node of &lt;ip&gt;
     * @return True if parsed with no errors, false otherwise
     */
    private boolean parseInterface(OccpHost curHost, Node node) {
        boolean parseErrorEncountered = false;
        String name = null, network = null, temp = null, ipv4Address = null, ipv4Netmask = null;
        // TODO message names
        boolean auto = true;

        OccpNetworkInterface.Config config = OccpNetworkInterface.Config.INVALID;
        OccpNetworkInterface.InterfaceType type = OccpNetworkInterface.InterfaceType.INVALID;

        // Look for required name attribute

        try {
            name = safeGetStringAttribute(node, "name");

            network = this.safeGetStringAttribute(node, "network", null);
            if (network != null) {
                type = OccpNetworkInterface.InterfaceType.PHYSICAL;
            } else {
                type = OccpNetworkInterface.InterfaceType.VIRTUAL;
            }

            // Look for optional config attribute
            temp = safeGetStringAttribute(node, "config", "dhcp");
            if (temp.equalsIgnoreCase("dhcp")) {
                config = OccpNetworkInterface.Config.DHCP;
            } else if (temp.equalsIgnoreCase("none")) {
                config = OccpNetworkInterface.Config.NONE;
            } else if (temp.equalsIgnoreCase("static")) {
                config = OccpNetworkInterface.Config.STATIC;
            } else {
                parseErrorEncountered = true;
                logger.severe(curHost.getLabel()
                        + ": Invalid value for interface config: can be dhcp, none, or static, default is dhcp");
            }
            OccpNetworkInterface occpInterface = new OccpNetworkInterface(name, type, config);

            // Look for optional auto attribute
            auto = safeGetBooleanAttribute(node, "auto", true);
            occpInterface.setAuto(auto);

            // Look for optional (in some cases required) ipv4 attribute
            temp = safeGetStringAttribute(node, "ipv4", null);
            if (temp != null) {
                // Check if it is valid
                try {
                    ipv4Address = OccpNetworkInterface.v4cidrToAddress(temp);
                    ipv4Netmask = OccpNetworkInterface.v4cidrToNetmask(temp);
                } catch (IllegalArgumentException exception) {
                    parseErrorEncountered = true;
                    logger.severe(curHost.getLabel() + "/" + name + ": ipv4 is expected in CIDR format but got " + temp);
                }
            } else {
                // A case when it is not optional
                if (config == OccpNetworkInterface.Config.STATIC) {
                    parseErrorEncountered = true;
                    logger.severe(curHost.getLabel() + "/" + name + ": You must provide IPv4 with config=\"static\"!");
                }
            }

            temp = safeGetStringAttribute(node, "broadcast", null);
            if (temp != null && !occpInterface.setV4Broadcast(temp)) {
                parseErrorEncountered = true;
                logger.severe(curHost.getLabel() + "/" + name + ": Invalid broadcast address encountered: " + temp);
            }
            temp = safeGetStringAttribute(node, "gateway", null);
            if (temp != null && !occpInterface.setV4Gateway(temp)) {
                parseErrorEncountered = true;
                logger.severe(curHost.getLabel() + "/" + name + ": Invalid gateway address encountered: " + temp);
            }

            occpInterface.setNetwork(network);
            if (ipv4Address != null && ipv4Netmask != null) {
                if (!occpInterface.setV4Address(ipv4Address, ipv4Netmask)) {
                    parseErrorEncountered = true;
                    logger.severe(curHost.getLabel() + "/" + name + ": Invalid networking configuration: ip="
                            + ipv4Address + " netmask=" + ipv4Netmask);
                }
                if (network != null && network.equals("fake-internet") && occpInterface.getV4Gateway() == null) {
                    logger.severe(curHost.getLabel() + ": Global static configurations must specify expected gateway");
                    parseErrorEncountered = true;
                }
            }

            NodeList children = node.getChildNodes();

            // Look through each child
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeName().equalsIgnoreCase("route")) {
                    String subnet = safeGetStringAttribute(child, "subnet");
                    occpInterface.addRoute(subnet);
                }
            }

            // All data for this interface has been considered, add it to our
            // list
            curHost.addInterface(occpInterface);
        } catch (MissingRequired e) {
            logger.log(Level.SEVERE, "Expected value missing", e);
        } catch (MismatchedExpectedType e) {
            logger.log(Level.SEVERE, "Encountered type was incorrect", e);
        }
        return !parseErrorEncountered;
    }

    /**
     * Parse an OCCP/host tag. See the wiki for a full definition of attributes
     * and substructure. The index is used to
     * generate a generic label if it isn't specified or it meant to be a
     * duplicate, but we need to differentiate.
     * 
     * @param node - DOM Node for a Host
     * @param index - unique number (small) for this host
     * @return True if parsed with no errors, false otherwise
     */
    private boolean parseHost(Node node, int index) {
        boolean parseErrorEncountered = false;
        String label = null, baseVM, ovaName, domain, hostname, isoName;
        int clones, phase = -1, ram;
        boolean configureInterfaces = true;
        // Look for required domain attribute
        try {
            // Look for required label attribute
            label = safeGetStringAttribute(node, "label").toLowerCase();
            domain = safeGetStringAttribute(node, "domain", null);
            if (Arrays.asList(RESERVED_HOST_NAMES).contains(label)) {
                parseErrorEncountered = true;
                logger.severe(label + ": A host can not be any of: " + StringUtils.join(RESERVED_HOST_NAMES, ", "));
            }
            // Look for required name attribute
            hostname = safeGetStringAttribute(node, "hostname");
            baseVM = safeGetStringAttribute(node, "basevm", null);
            ovaName = safeGetStringAttribute(node, "ovaname", label + ".ova");
            isoName = safeGetStringAttribute(node, "iso", null);
            try {
                phase = safeGetIntegerAttribute(node, "phase");
            } catch (XMLParseException e) {
                parseErrorEncountered = true;
                logger.severe(label + ": phase attribute is required");
            }
            if (isoName != null && phase != 2) {
                parseErrorEncountered = true;
                logger.severe(label + ": ISO-based VMs must be phase 2");
            }
            // Look for clone attribute or use default
            clones = safeGetIntegerAttribute(node, "clones", 0);
            if (clones < 0) {
                parseErrorEncountered = true;
                logger.severe(label + ": Invalid value for clones, must be >= 0");
            }
            configureInterfaces = safeGetBooleanAttribute(node, "configureInterfaces", true);
            ram = safeGetIntegerAttribute(node, "ram", 0);
            if (ram < 0) {
                parseErrorEncountered = true;
                logger.severe(label + ": Invalid value for ram, must be > 0");
            }
            OccpHost host = new OccpHost(label, hostname, domain);
            host.setBaseVM(baseVM);
            host.setInterfaceConfig(configureInterfaces);
            host.setOvaName(ovaName);
            if (isoName != null) {
                host.setIsoName(OccpAdmin.scenarioBaseDir.resolve(isoName).toString());
            }
            if (!host.setPhase(phase)) {
                parseErrorEncountered = true;
                logger.severe(label + ": Invalid value for phase, must be 1 or 2");
            }
            host.setRam(ram);

            // Additional data for this host
            NodeList children = node.getChildNodes();

            // Look through each child
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);

                // Determine if it is a tag we should parse
                if (child.getNodeName().equalsIgnoreCase("interface")) {
                    // It is an interface tag, parse as such
                    if (!this.parseInterface(host, child)) {
                        parseErrorEncountered = true;
                    }

                } else if (child.getNodeName().equalsIgnoreCase("content")) {
                    // It is a content tag, parse as such
                    if (!this.parseContent(host, child)) {
                        parseErrorEncountered = true;
                    }
                }
                // else, we don't care. Ignore the tag
            }

            if (label.equals(GAMESERVER_NAME)) {
                boolean foundGameServerContentPack = false;
                for (ContentPackInfo contentPack : host.getContentPacks()) {
                    if (contentPack.getPackName().equals("gameserver")) {
                        foundGameServerContentPack = true;
                        break;
                    }
                }
                if (!foundGameServerContentPack) {
                    parseErrorEncountered = true;
                    logger.severe("The gameserver must have the content pack \"gameserver\" and have a files directory");
                }
                if (clones != 0) {
                    parseErrorEncountered = true;
                    logger.severe("Clones of the gameserver are not allowed");
                }
            }

            if (clones == 0) {
                host.setIntermediate(false); // Explicitly set false
                if (!this.addHost(host)) {
                    parseErrorEncountered = true;
                }
            } else {
                // Use the first one for the template, clone later
                host.setIntermediate(true);
                if (!this.addHost(host)) {
                    parseErrorEncountered = true;
                }
                for (int cloneNumber = 0; cloneNumber < clones; ++cloneNumber) {
                    String newLabel = label + "-" + (cloneNumber + 1);
                    String newHostname = hostname + "-" + (cloneNumber + 1);
                    OccpHost clone = host.makeClone(newLabel, newHostname);
                    clone.setOvaName(null);
                    if (host.getPhase() == 2) {
                        clone.setPhase(2);
                    } else {
                        clone.setPhase(1);
                    }
                    clone.setClone(label);
                    if (!this.addHost(clone)) {
                        parseErrorEncountered = true;
                    }
                }
            }
        } catch (MismatchedExpectedType | MissingRequired e) {
            parseErrorEncountered = true;
            if (label == null) {
                logger.severe("A label was missing on a host node");
            } else {
                logger.log(Level.SEVERE, "Host with label: " + label + " encountered parsing errors", e);
            }
        }
        return !parseErrorEncountered;
    }

    // Utility Methods -----------------------------------------------------
    /**
     * Add a OccpHost to our internal list of them
     * 
     * @param host OccpHost to add
     * @return Whether we added it successfully or not
     */
    public boolean addHost(OccpHost host) {
        boolean addedSuccsessfully = false;
        String label = host.getLabel();

        // Check if we have encountered this label previously
        if (!this.hosts.containsKey(label)) {
            this.hosts.put(label, host);
            addedSuccsessfully = true;
        } else {
            // Label must be unique
            logger.severe("Duplicate host label: " + label);
            addedSuccsessfully = false;
        }
        return addedSuccsessfully;
    }

    /**
     * Remove a host by the given label from our list
     * 
     * @param label - The label of the VM to remove
     */
    public void removeHost(String label) {
        if (hosts != null) {
            hosts.remove(label);
        }
    }

    /**
     * Look for the given attribute in the given node, enforce it is found and
     * is a boolean
     * 
     * @param node Node to look in
     * @param attrname Attribute to look for
     * @return The boolean value found
     * @throws XMLParseException if the attribute is not found or is not a
     *             boolean
     */
    @SuppressWarnings("unused")
    private boolean safeGetBooleanAttribute(Node node, String attrname) throws XMLParseException {
        String tempResult = safeGetStringAttribute(node, attrname);
        boolean result;

        if (tempResult.equalsIgnoreCase("true")) {
            result = true;
        } else if (tempResult.equalsIgnoreCase("false")) {
            result = false;
        } else {
            throw new MismatchedExpectedType("\"" + attrname + "\" expects an true or false but got \"" + tempResult
                    + "\"");
        }
        return result;
    }

    /**
     * Look for the given attribute in the given node, use default if not found
     * and sure value is boolean
     * 
     * @param node Node to look in
     * @param attrname Attribute to look for
     * @param def The default value
     * @return The boolean value found or the default
     * @throws MismatchedExpectedType if the found value is not a boolean
     * @throws MissingRequired
     */
    private boolean safeGetBooleanAttribute(Node node, String attrname, boolean def)
            throws MismatchedExpectedType, MissingRequired {
        String tempResult = safeGetStringAttribute(node, attrname, Boolean.toString(def));
        boolean result = def;

        if (tempResult.equalsIgnoreCase("true")) {
            result = true;
        } else if (tempResult.equalsIgnoreCase("false")) {
            result = false;
        } else {
            throw new MismatchedExpectedType("\"" + attrname + "\" expects an true or false but got \"" + tempResult
                    + "\"");
        }
        return result;
    }

    /**
     * Look for the given attribute in the given node, enforce it is found and
     * is an integer
     * 
     * @param node Node to look in
     * @param attrname Attribute to look for
     * @return The Attribute's integer value
     * @throws XMLParseException if the required attribute is not found or is
     *             not an integer
     */
    private int safeGetIntegerAttribute(Node node, String attrname) throws XMLParseException {
        String tempResult = safeGetStringAttribute(node, attrname);
        int result = 0;
        try {
            result = Integer.parseInt(tempResult);
        } catch (NumberFormatException nfe) {
            throw new MismatchedExpectedType("\"" + attrname + "\" expects an integer but got \"" + tempResult + "\"");
        }
        return result;
    }

    /**
     * Look for the given attribute in the given node, use value found or the
     * default. Ensure it is an integer
     * 
     * @param node Node to look in
     * @param attrname Attribute to look for
     * @param def The default value to use
     * @return The Attribute's integer value if found or the default
     * @throws MismatchedExpectedType If the encountered value is not an integer
     * @throws MissingRequired
     */
    private int safeGetIntegerAttribute(Node node, String attrname, int def)
            throws MismatchedExpectedType, MissingRequired {
        String tempResult = safeGetStringAttribute(node, attrname, "" + def);
        int result = def;
        try {
            result = Integer.parseInt(tempResult);
        } catch (NumberFormatException nfe) {
            throw new MismatchedExpectedType("\"" + attrname + "\" expects an integer but got \"" + tempResult + "\"");
        }
        return result;
    }

    /**
     * Look for the given attribute in the given node. Throw an exception if the
     * attribute is missing
     * 
     * @param node Node to look in
     * @param attrname Attribute to look for
     * @return String value of the attribute
     * @throws MissingRequired if the attribute is not found
     */
    private String safeGetStringAttribute(Node node, String attrname) throws MissingRequired {
        if (node == null) {
            throw new MissingRequired("\"" + attrname + "\" was required but not found");
        }

        nnm = node.getAttributes();
        if (nnm == null) {
            throw new MissingRequired("\"" + attrname + "\" was required but not found");
        }

        Node attr = nnm.getNamedItem(attrname);

        if (attr == null) {
            throw new MissingRequired("\"" + attrname + "\" was required but not found");
        }

        String value = ((Attr) attr).getValue();
        // Handles OCCP variables
        value = getValue(value);
        if (value == null || value.isEmpty()) {
            throw new MissingRequired("\"" + attrname + "\" is required to have a value but did not");
        }

        return value;
    }

    /**
     * Look for the given attribute in the given node, use the given default if
     * it is not found
     * 
     * @param node Node to look in
     * @param attrname Attribute to look for
     * @param def Default value to use
     * @return The attribute's value if available or the given default
     * @throws MissingRequired
     */
    private String safeGetStringAttribute(Node node, String attrname, String def) throws MissingRequired {

        if (node == null) {
            return def;
        }

        nnm = node.getAttributes();
        if (nnm == null) {
            return def;
        }

        Node attr = nnm.getNamedItem(attrname);

        if (attr == null) {
            return def;
        }

        String value = ((Attr) attr).getValue();
        // Handles OCCP variables
        value = getValue(value);

        if (value != null && value.isEmpty()) {
            throw new MissingRequired("\"" + attrname + "\" must have a non-empty value if specified");
        }

        // Note that value could be null for: <tag attr>
        return value != null ? value : def;
    }

    /**
     * From the given input either use the input or replace an OCCP variable with its value
     * 
     * @param encountered the input to test
     * @return the input or the value if input was a variable
     */
    private String getValue(String encountered) {
        String result = encountered;

        // Variables should start with "${occp:" and enclose the variable name with "}"
        // ${occp:foo} or ${occp:bar}

        // When variables are Arrays:
        // ${occp:foo[]} for the entire array -> ['element0', 'element1', ..., 'elementN']
        // ${occp:bar[X] to select the Xth element of the array
        // If ${occp:foo} is used and foo is an array it is considered an error

        // regex for occp variable matching
        Pattern occpVariablePattern = Pattern.compile("\\$\\{occp:([a-zA-Z]+\\w*)(\\[(\\d*)\\])?\\}");
        Matcher occpVariableMatcher = occpVariablePattern.matcher(encountered);

        while (occpVariableMatcher.find()) {
            // Extract the variable name
            String variableName = occpVariableMatcher.group(1);

            // Extract the element if available
            String element = occpVariableMatcher.group(3);

            // Storage for the lookup value
            String value = null;

            // Determine if array syntax is being used
            if (occpVariableMatcher.group(2) != null) {
                // Is an array but is a specific element being requested?
                if (element != null && !element.equalsIgnoreCase("")) {
                    // Yes, convert to integer and lookup specified value
                    int index = Integer.parseInt(element);
                    value = this.occpVariables.getVariableArray(variableName, index);
                } else {
                    // No, lookup all values
                    value = this.occpVariables.getVariableArray(variableName);
                }
                if (value != null) {
                    // Sanitize the lookup so regex will not choke on reserved characters
                    value = Matcher.quoteReplacement(value);
                    // Replace the variable with the value, then consider the new text with the replacement in case
                    // there are more variables left.
                    result = occpVariableMatcher.replaceFirst(value);
                } else {

                    result = occpVariableMatcher.replaceFirst("OCCP_VARIABLE_LOOKUP_FAILED");
                }
            } else {
                // Just a normal variable, no array syntax used
                value = this.occpVariables.getVariable(variableName);
                if (value != null) {
                    value = Matcher.quoteReplacement(value);
                    result = occpVariableMatcher.replaceFirst(value);
                } else {
                    if (this.occpVariables.getVariableArray(variableName) != null) {
                        logger.severe("Attempting to use the variable array \"" + variableName
                                + "\" as normal variable. Did you mean \"" + variableName + "[]\"?");
                    }
                    result = occpVariableMatcher.replaceFirst("OCCP_VARIABLE_LOOKUP_FAILED");
                }
            }
            // Update to use the newly processed text
            occpVariableMatcher = occpVariablePattern.matcher(result);
        }
        return result;
    }

    /**
     * Data structure to hold OCCP variables
     */
    public static class OccpVariables {

        // Will store OCCP variables and their values
        private final Map<String, String> occpVariables;

        // Will store OCCP variable arrays and their values
        private final Map<String, ArrayList<String>> occpVariableArrays;
        private boolean aVariableLookupFailed = false;

        /**
         * Default constructor
         */
        public OccpVariables() {
            occpVariables = new HashMap<>();
            occpVariableArrays = new HashMap<>();
        }

        /**
         * Determines if a lookup has ever failed
         * 
         * @return true if a lookup had failed, false if no lookups have ever failed during the life of the object.
         */
        public boolean aLookupFailed() {
            return this.aVariableLookupFailed;
        }

        /**
         * Attempts to merge the given variables in to this structure. Will fail if naming conflicts exist.
         * 
         * @param newVariables - The OccpVariables to merge in to this structure
         * @return true if merge was successful, false if any fail to merge.
         */
        public boolean mergeVariables(final OccpVariables newVariables) {
            boolean encounteredError = false;
            // Merge the normal variables
            for (Entry<String, String> entry : newVariables.occpVariables.entrySet()) {
                String newVariable = entry.getKey();
                String newVariableValue = entry.getValue();
                if (!setVariable(newVariable, newVariableValue, true)) {
                    // Probably a naming conflict
                    encounteredError = true;
                }
            }
            // Merge the array variables
            for (Entry<String, ArrayList<String>> entry : newVariables.occpVariableArrays.entrySet()) {
                String newVariable = entry.getKey();
                ArrayList<String> newVariableValue = entry.getValue();
                if (!setVariable(newVariable, newVariableValue, true)) {
                    // Probably a naming conflict
                    encounteredError = true;
                }
            }
            return !encounteredError;
        }

        /**
         * Attempt to set the given variable to the given value. This will not overwrite an existing variable and will
         * return false if it is attempted. Logs successful setting values.
         * 
         * @param variableName - The variable to set
         * @param value - The value to set it to
         * @return True if successfully set, false if the variable already existed
         */
        public boolean setVariable(String variableName, String value) {
            return this.setVariable(variableName, value, false);
        }

        /**
         * Attempt to set the given variable to the given value. This will not overwrite an existing variable and will
         * return false if it is attempted.
         * 
         * @param variableName - The variable to set
         * @param value - The value to set it to
         * @param verbose -Whether or not to log the setting operation values
         * @return True if successfully set, false if the variable already existed
         */
        public boolean setVariable(String variableName, String value, boolean verbose) {
            boolean successful = false;
            // Is it already a variable?
            if (!occpVariables.containsKey(variableName)) {
                // Is it already an array?
                if (!occpVariableArrays.containsKey(variableName)) {
                    // It does not exist so we can safely create it
                    occpVariables.put(variableName, value);
                    successful = true;
                    if (verbose) {
                        logger.finer("Set OCCP variable: \"" + variableName + "\" to \"" + value + "\"");
                    }
                } else {
                    // Already exists as an array
                    successful = false;
                    logger.severe("Attempting to reassign OCCP variable array: \"" + variableName
                            + "\" to be a variable.");
                }
            } else {
                // Already exists as a variable
                successful = false;
                logger.severe("Attempting to reassign OCCP variable: \"" + variableName + "\" from \""
                        + occpVariables.get(variableName) + "\" to \"" + value + "\"");
            }
            return successful;
        }

        /**
         * Attempt to set the given variable to the given value array. This will not overwrite an existing variable and
         * will return false if it is attempted. Logs successful setting values.
         * 
         * @param variableName - The variable to set
         * @param value - The value array to set it to
         * @return True if successfully set, false if the variable already existed
         */

        public boolean setVariable(String variableName, ArrayList<String> value) {
            return this.setVariable(variableName, value, false);
        }

        /**
         * Attempt to set the given variable to the given value array. This will not overwrite an existing variable and
         * will return false if it is attempted
         * 
         * @param variableName - The variable to set
         * @param value - The value array to set it to
         * @param verbose - Whether or not to log the setting operation
         * @return True if successfully set, false if the variable already existed
         */

        public boolean setVariable(String variableName, ArrayList<String> value, boolean verbose) {
            boolean successful = false;
            // Is it already an array?
            if (!occpVariableArrays.containsKey(variableName)) {
                // Is it already a variable?
                if (!occpVariables.containsKey(variableName)) {
                    // It does not exist so we can safely create it
                    occpVariableArrays.put(variableName, value);
                    successful = true;
                    if (verbose) {
                        StringBuilder finerOutput = new StringBuilder("Set OCCP variable: \"" + variableName
                                + "\" as an array with values:");
                        for (int elementIndex = 0; elementIndex < value.size(); elementIndex++) {
                            finerOutput.append("\n Element ");
                            finerOutput.append(elementIndex);
                            finerOutput.append(" Value: \"");
                            finerOutput.append(value.get(elementIndex));
                            finerOutput.append("\"");
                        }
                        logger.finer(finerOutput.toString());
                    }
                } else {
                    // Already existed as a variable
                    successful = false;
                    logger.severe("Attempting to reassign OCCP variable: \"" + variableName + "\" to be an array.");
                }
            } else {
                // Already existed as an array
                successful = false;
                logger.severe("Attempting to reassign OCCP variable array: \"" + variableName + "\"");
            }
            return successful;
        }

        /**
         * Looks up the given variable and resolves it to a value if it was declared
         * 
         * @param variableName - the variable to resolve
         * @return the value of the declared variable or null for an undeclared variable
         */
        public String getVariable(String variableName) {
            String result = occpVariables.get(variableName);
            if (result == null) {
                logger.severe("The OCCP variable \"" + variableName + "\" is being used but never declared");
                this.aVariableLookupFailed = true;
            }
            return result;
        }

        /**
         * Returns the entire array
         * 
         * @param variableName - The array to return
         * @return The entire array or null if not possible
         */
        public String getVariableArray(final String variableName) {
            StringBuilder result = new StringBuilder();
            ArrayList<String> lookup = occpVariableArrays.get(variableName);
            if (lookup != null) {
                result.append("[");
                for (String element : lookup) {
                    result.append("'");
                    result.append(element);
                    result.append("',");
                }
                result.append("]");
            } else {
                logger.severe("The OCCP variable array \"" + variableName + "\" is being used but never delcared");
                this.aVariableLookupFailed = true;
                return null;
            }
            return result.toString();
        }

        /**
         * Retrieves the requested element from the array or null if the index is impossible to fetch
         * 
         * @param variableName - The name of the array to lookup
         * @param index - The requested index
         * @return - The looked up value or null if impossible to fetch
         */
        public String getVariableArray(final String variableName, final int index) {
            String result = null;
            ArrayList<String> lookup = occpVariableArrays.get(variableName);
            if (lookup != null) {
                try {
                    result = lookup.get(index);
                } catch (IndexOutOfBoundsException exception) {
                    logger.severe("The index " + index + " for the OCCP variable array \"" + variableName
                            + "\" is out of bounds");
                    this.aVariableLookupFailed = true;
                }
            } else {
                logger.severe("The OCCP variable array \"" + variableName + "\" is being used but never delcared");
                this.aVariableLookupFailed = true;
            }
            return result;
        }
    }
}
