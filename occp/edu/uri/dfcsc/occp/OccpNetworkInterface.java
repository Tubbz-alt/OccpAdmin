package edu.uri.dfcsc.occp;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.net.util.SubnetUtils;
import org.apache.commons.validator.routines.InetAddressValidator;

/**
 * SoftwareInterface Class to represent a Network Interface's options in a software context
 */
public class OccpNetworkInterface implements Cloneable {

    private final InetAddressValidator addressValidator = new InetAddressValidator();

    private String name = null, // The interface name i.e. eth0
            network = null, ipv4Address = null, // IPv4 Address
            ipv4Netmask = null, // IPv4 Netmask
            ipv4Gateway = null, // IPv4 Gateway
            ipv4Broadcast = null; // IPv4 Broadcast

    // The interface type
    private InterfaceType type = OccpNetworkInterface.InterfaceType.PHYSICAL;

    private boolean auto = true; // For systems with notion of automatic/on boot interface up

    // The configuration for this interface
    private Config config = OccpNetworkInterface.Config.DHCP;

    private final List<String> routes;

    /**
     * Constructor
     * 
     * @param name The name of the interface
     * @param type The type of interface
     * @param config How the interface will be configured
     */
    public OccpNetworkInterface(final String name, final InterfaceType type, final Config config) {
        this.name = name;
        this.type = type;
        this.config = config;
        this.routes = new ArrayList<String>();
    }

    // Setters & Getters ---------------------------------------------------

    // Getters
    /**
     * @return Whether or not the interface should be brought up on boot
     */
    public boolean getAuto() {
        return this.auto;
    }

    /**
     * @return The Config enum for this interface
     */
    public Config getConfig() {
        return this.config;
    }

    /**
     * @return The name of this interface
     */
    public String getName() {
        return this.name;
    }

    /**
     * @return The network this interface is attached to
     */
    public String getNetwork() {
        if (this.network != null) {
            return this.network;
        }
        return "";
    }

    /**
     * @return The type of NIC represented by the enum InterfaceType
     */
    public InterfaceType getType() {
        return this.type;
    }

    /**
     * @return the IPv4 address or null if not set
     */
    public String getV4Address() {
        return this.ipv4Address;
    }

    /**
     * @return the IPv4 broadcast address or null if not set
     */
    public String getV4Broadcast() {
        return this.ipv4Broadcast;
    }

    /**
     * @return the IPv4 gateway address or null if not set
     */
    public String getV4Gateway() {
        return this.ipv4Gateway;
    }

    /**
     * @return the IPv4 netmask or null if not set
     */
    public String getV4Netmask() {
        return this.ipv4Netmask;
    }

    /**
     * @return true if an IPv4 address was set
     */
    public boolean hasV4Address() {
        return this.ipv4Address != null;
    }

    /**
     * @return true if an IPv4 broadcast address was set
     */
    public boolean hasV4Broadcast() {
        return this.ipv4Broadcast != null;
    }

    /**
     * @return true if an IPv4 gateway address was set
     */
    public boolean hasV4Gateway() {
        return this.ipv4Gateway != null;
    }

    // Setters
    /**
     * Sets whether or not the interface should be brought up on boot or not
     * 
     * @param auto true if the interface should be brought up on boot, false otherwise
     */
    public void setAuto(final boolean auto) {
        this.auto = auto;
    }

    /**
     * Sets the hypervisor network this NIC is attached to
     * 
     * @param network The name of the network
     */
    public void setNetwork(final String network) {
        this.network = network;
        if (OccpAdmin.instanceId != null && !network.equals(OccpParser.INTERNET_NAME)) {
            this.network += "-" + OccpAdmin.instanceId;
        }
    }

    /**
     * Sets the IPv4 Address &amp; Netmask for this interface
     * 
     * @param ipv4 String representing the IPv4 Address
     * @param netmask String representing the IPv4 Netmask
     * @return Whether or not the address was added successfully
     */
    public boolean setV4Address(String ipv4, String netmask) {
        boolean didSetSuccesfully = false;
        if (addressValidator.isValidInet4Address(ipv4)) {
            this.ipv4Address = ipv4;
            this.ipv4Netmask = netmask;
            didSetSuccesfully = true;
        }
        return didSetSuccesfully;
    }

    /**
     * Sets the IPv4 Broadcast address for this interface
     * 
     * @param broadcast String representing the IPv4 Broadcast address
     * @return Whether or not the Broadcast address was added successfully
     */
    public boolean setV4Broadcast(String broadcast) {
        boolean didSetSuccesfully = false;
        if (addressValidator.isValidInet4Address(broadcast)) {
            this.ipv4Broadcast = broadcast;
            didSetSuccesfully = true;
        }
        return didSetSuccesfully;
    }

    /**
     * Sets the IPv4 Gateway address for this interface
     * 
     * @param gateway String representing the IPv4 Gateway address
     * @return Whether or not the Gateway address was added successfully
     */
    public boolean setV4Gateway(String gateway) {
        boolean didSetSuccesfully = false;
        if (addressValidator.isValidInet4Address(gateway)) {
            this.ipv4Gateway = gateway;
            didSetSuccesfully = true;
        }
        return didSetSuccesfully;
    }

    // Classes & Types -----------------------------------------------------

    /**
     * Config enum that represents the configuration method
     */
    public static enum Config {
        /**
         * For configurations using DHCP
         */
        DHCP,
        /**
         * For configurations that will not be handled by the Admin Program
         */
        NONE,
        /**
         * For static configurations
         */
        STATIC,
        /**
         * Invalid configuration type
         */
        INVALID
    }

    /**
     * InterfaceType enum that represents the interface type
     */
    public static enum InterfaceType {
        /**
         * For physical interfaces (or as physical as they get for VMs)
         */
        PHYSICAL,
        /**
         * For virtual interfaces
         */
        VIRTUAL,
        /**
         * An invalid interface type
         */
        INVALID
    }

    // Utility Methods -----------------------------------------------------

    @Override
    public OccpNetworkInterface clone() {
        OccpNetworkInterface result = new OccpNetworkInterface(this.getName(), this.getType(), this.getConfig());

        result.setAuto(this.getAuto());
        if (this.hasV4Address()) {
            result.setV4Address(this.getV4Address(), this.ipv4Netmask);
            if (this.hasV4Broadcast()) {
                result.setV4Broadcast(this.getV4Broadcast());
            }
            if (this.hasV4Gateway()) {
                result.setV4Gateway(this.getV4Gateway());
            }
        }
        result.setNetwork(this.getNetwork());
        result.routes.addAll(this.routes);
        return result;
    }

    /**
     * Utility method to map enum to string name
     * 
     * @param config The Config to translate
     * @return String name translation from enum
     */
    public static final String configToString(Config config) {
        String result;
        switch (config) {
        case DHCP:
            result = "dhcp";
            break;
        case NONE:
            result = "none";
            break;
        case STATIC:
            result = "static";
            break;
        case INVALID:
        default:
            result = "invalid";
        }
        return result;
    }

    /**
     * Utility method to map enum to string name
     * 
     * @param type The InterfaceType to translate
     * @return String name translation from enum
     */
    public static final String interfaceTypeToString(InterfaceType type) {
        String result;
        switch (type) {
        case PHYSICAL:
            result = "physical";
            break;
        case VIRTUAL:
            result = "virtual";
            break;
        case INVALID:
        default:
            result = "invalid";
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append("Network Interface:+++++" + this.getName() + "\n");
        result.append("Type: " + interfaceTypeToString(getType()) + "\n");
        result.append("Network: " + this.getNetwork() + "\n");
        result.append("Config: " + configToString(getConfig()) + "\n");
        result.append("Auto: " + this.getAuto() + "\n");

        if (this.hasV4Address()) {
            result.append("IPv4 Address: " + this.getV4Address() + "\n");
            result.append("IPv4 Netmask: " + this.getV4Netmask() + "\n");
        }
        if (this.hasV4Broadcast()) {
            result.append("IPv4 Broadcast: " + this.getV4Broadcast() + "\n");
        }
        if (this.hasV4Gateway()) {
            result.append("IPv4 Gateway: " + this.getV4Gateway() + "\n");
        }

        result.append("End Network Interface:+++++" + this.getName());
        return result.toString();
    }

    /**
     * Takes CIDR style IPv4 and produces IPv4 Address
     * 
     * @param cidr IPv4 Address and Netmask in CIDR format
     * @return String representation of IPv4 Address
     * @throws IllegalArgumentException
     */
    public static String v4cidrToAddress(String cidr) throws IllegalArgumentException {
        String result = "";
        SubnetUtils.SubnetInfo info = new SubnetUtils(cidr).getInfo();
        result = info.getAddress();
        return result;
    }

    /**
     * Takes CIDR style IPv4 and produces IPv4 Netmask
     * 
     * @param cidr IPv4 Address and Netmask in CIDR format
     * @return String representation of IPv4 Netmask
     * @throws IllegalArgumentException
     */
    public static String v4cidrToNetmask(String cidr) throws IllegalArgumentException {
        String result = "";
        SubnetUtils.SubnetInfo info = new SubnetUtils(cidr).getInfo();
        result = info.getNetmask();
        return result;
    }

    /**
     * Adds the given subnet to the routes
     * 
     * @param subnet - The subnet to add
     */
    public void addRoute(final String subnet) {
        this.routes.add(subnet);
    }

    /**
     * Returns the list of routes
     * 
     * @return The list of routes
     */
    public List<String> getRoutes() {
        return this.routes;
    }
};
