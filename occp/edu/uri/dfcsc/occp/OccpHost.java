package edu.uri.dfcsc.occp;

import java.util.ArrayList;
import java.util.List;

/**
 * A class to represent an OCCP host's properties
 */
public class OccpHost {
    /*
     * Host's label should be unique in OCCP XML
     */
    private String clone = null, baseVM = null, domain = null, hostname = null, label = "", setupIP = null;

    // Whether or not we are responsible for generating the puppet code
    // to configure the interfaces. True means we are, false assumes the user
    // will configure the interfaces themselves
    private boolean interfaceConfig = true;

    // Our interfaces
    private final ArrayList<OccpNetworkInterface> interfaces = new ArrayList<OccpNetworkInterface>();

    // Our content pack(s)
    private final ArrayList<ContentPackInfo> contentPacks = new ArrayList<ContentPackInfo>();

    private String ovaName;

    private String isoName = null;

    private int phase;

    private boolean intermediate = false;

    private int ram = 0;

    /**
     * Constructor
     * 
     * @param label the host's label
     * @param hostname the host's hostname
     * @param domain the host's domain
     */
    public OccpHost(String label, String hostname, String domain) {
        this.label = label;
        this.hostname = hostname;
        this.domain = domain;
    }

    // Setters & Getters ---------------------------------------------------

    // Getters
    /**
     * @return The name of the BaseVM this host is based from or null if not set
     */
    public String getBaseVM() {
        return this.baseVM;
    }

    /**
     * @return return The name of the VM this host is cloned from or null if not set
     */
    public String getClone() {
        return clone;
    }

    /**
     * @return The list of content packs this host is using
     */
    public ArrayList<ContentPackInfo> getContentPacks() {
        return this.contentPacks;
    }

    /**
     * @return The domain this host is using or null
     */
    public String getDomain() {
        return this.domain;
    }

    /**
     * @return Whether or not the interfaces should be configured by the OCCP Admin program
     */
    public boolean getInterfaceConfig() {
        return this.interfaceConfig;
    }

    /**
     * @return Whether or not this host is an intermediate VM
     */
    public boolean getIntermediate() {
        return this.intermediate;
    }

    /**
     * @return This host's label
     */
    public String getLabel() {
        return this.label;
    }

    /**
     * @return The hostname of this host
     */
    public String getHostname() {
        return this.hostname;
    }

    /**
     * @return The name of the ova file
     */
    public String getOvaName() {
        return ovaName;
    }

    /**
     * @return The name of the iso file
     */
    public String getIsoName() {
        return isoName;
    }

    /**
     * @return The list of interfaces for this host
     */
    public ArrayList<OccpNetworkInterface> getInterfaces() {
        return this.interfaces;
    }

    /**
     * @return This host's desired phase
     */
    public int getPhase() {
        return phase;
    }

    /**
     * @return The amount of RAM for this host. Zero if not specified
     */
    public int getRam() {
        return this.ram;
    }

    /**
     * @return The IP address to use during setup
     */
    public String getSetupIP() {
        return this.setupIP;
    }

    // Setters
    /**
     * Sets the BaseVM to the given name
     * 
     * @param baseVM The name of the Base VM to set to
     */
    public void setBaseVM(final String baseVM) {
        this.baseVM = baseVM;
    }

    /**
     * Sets the name of the machine this one is cloned from
     * 
     * @param clone the name of the machine this one is cloned from
     */
    public void setClone(String clone) {
        this.clone = clone;
    }

    /**
     * @param ovaName The name for the ova file
     */
    public void setOvaName(final String ovaName) {
        this.ovaName = ovaName;
    }

    /**
     * @param isoName The name of the iso for iso-only machines
     */
    public void setIsoName(String isoName) {
        this.isoName = isoName;
    }

    /**
     * @param config - Set whether or not the Admin program is responsible for configuring the networks
     */
    public void setInterfaceConfig(final boolean config) {
        this.interfaceConfig = config;
    }

    /**
     * @param isIntermediate Set whether or not it is an intermediate VM
     */
    public void setIntermediate(boolean isIntermediate) {
        this.intermediate = isIntermediate;
    }

    /**
     * @param phase - The phase to set, must be one or two
     * @return - True if a valid value was given and set, false otherwise
     */
    public boolean setPhase(final int phase) {
        boolean setSuccessfully = false;
        if (phase > 0 && phase <= 2) {
            this.phase = phase;
            setSuccessfully = true;
        }
        return setSuccessfully;
    }

    /**
     * @param ram The amount of RAM in MB to set
     */
    public void setRam(final int ram) {
        this.ram = ram;
    }

    /**
     * @param setupIP Set the IP address to use during setup
     */
    public void setSetupIP(final String setupIP) {
        this.setupIP = setupIP;
    }

    // Classes & Types -----------------------------------------------------

    // Utility Methods -----------------------------------------------------

    /**
     * Add a ContentPackInfo object to our internal list of them
     * 
     * @param contentPack ContentPackInfo to be added
     */
    public void addContentPack(ContentPackInfo contentPack) {
        this.contentPacks.add(contentPack);
    }

    /**
     * Add a SoftwareInterface to our internal list of them
     * 
     * @param networkInterface - The NetworkInterface to add
     * @return Whether or not the add was successful
     */
    public boolean addInterface(OccpNetworkInterface networkInterface) {
        boolean foundDuplicateName = false, addedInferfaceSuccessfully = false;

        String nameToBeAdded = networkInterface.getName();

        // Look to see if we already have an interface with the same
        // name as the one we are trying to add

        for (OccpNetworkInterface currentInterface : this.interfaces) {
            if (currentInterface.getName().equalsIgnoreCase(nameToBeAdded)) {
                // We found a duplicate
                foundDuplicateName = true;
                break; // Escape the for each, no need to continue
            }
        }

        // If no duplicate was found, attempt to add
        if (!foundDuplicateName) {
            addedInferfaceSuccessfully = this.interfaces.add(networkInterface);
        }

        return addedInferfaceSuccessfully;
    }

    /**
     * makeClone but use the current hostname
     * 
     * @param newLabel the new label to assign the returned OccpHost
     * @return an OccpHost with similar data to this one
     */
    public OccpHost makeClone(String newLabel) {
        return this.makeClone(newLabel, this.getHostname());
    }

    /**
     * Return an OccpHost from the data from this host suitable for VM cloning
     * operations
     * 
     * @param newLabel the new label to assign to the returned OccpHost
     * @param newHostname the hostname to assign the returned OccpHost
     * @return an OccpHost with similar data to this one
     */
    public OccpHost makeClone(String newLabel, String newHostname) {
        OccpHost result = new OccpHost(newLabel, newHostname, this.getDomain());
        result.setBaseVM(this.getBaseVM());

        // Does not make sense to copy Clone info
        // Does not make sense to copy setupIP
        // Does not make sense to copy intermediate
        result.setPhase(this.getPhase());
        result.setOvaName(this.getOvaName());
        result.setInterfaceConfig(this.getInterfaceConfig());

        // Copy Interfaces
        for (OccpNetworkInterface currentInterface : this.getInterfaces()) {
            result.addInterface(currentInterface.clone());
        }

        // Copy content packs
        for (ContentPackInfo currentContentPack : this.getContentPacks()) {
            result.addContentPack(currentContentPack.clone());
        }

        // Copy RAM
        result.setRam(this.getRam());

        return result;
    }

    /**
     * @return - A list of the Physical Network names
     */
    public List<String> getPhyscialNetworkNames() {
        ArrayList<String> result = new ArrayList<>();
        for (OccpNetworkInterface currentInterface : this.getInterfaces()) {
            if (currentInterface.getType() == OccpNetworkInterface.InterfaceType.PHYSICAL) {
                result.add(currentInterface.getNetwork());
            }
        }
        return result;
    }

    /**
     * @return - A list of the Physical interfaces
     */
    public List<OccpNetworkInterface> getPhyscialInterfaces() {
        ArrayList<OccpNetworkInterface> result = new ArrayList<>();
        for (OccpNetworkInterface currentInterface : this.getInterfaces()) {
            if (currentInterface.getType() == OccpNetworkInterface.InterfaceType.PHYSICAL) {
                result.add(currentInterface);
            }
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append("Host:--------------" + this.getLabel() + "\n");
        result.append("Hostname: " + this.getHostname() + "\n");
        if (this.getDomain() != null) {
            result.append("Domain: " + this.getDomain() + "\n");
        }
        if (this.getBaseVM() != null) {
            result.append("BaseVM: " + this.getBaseVM() + "\n");
        }
        if (this.getClone() != null) {
            result.append("Clone: " + this.getClone() + "\n");
        }
        if (this.getInterfaceConfig()) {
            if (!this.interfaces.isEmpty()) {
                for (OccpNetworkInterface occpInterface : this.interfaces) {
                    result.append(occpInterface + "\n");
                }
            }
        } else {
            result.append("User has elected to configure all interfaces on their own\n");
        }

        result.append("End Host:--------------" + this.getLabel() + "\n");
        return result.toString();
    }
}
