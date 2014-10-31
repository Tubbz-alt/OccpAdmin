package edu.uri.dfcsc.occp;

import java.util.ArrayList;

import edu.uri.dfcsc.occp.exceptions.configmanager.ConfigManagerException;

/**
 * Children of this class are expected to control a configuration management
 * system. Not all methods will be applicable to every compatible CMS and should
 * follow the instructions for behavior described in the JavaDoc comments for
 * the methods.
 */
public abstract class ConfigManagerControl {
    protected final ArrayList<OccpHost> hosts;

    /**
     * @param hosts
     */
    public ConfigManagerControl(final ArrayList<OccpHost> hosts) {
        this.hosts = hosts;
    }

    /**
     * Do whatever is necessary for the configuration management system to be
     * effective. This method is intended to be called before any machine is
     * configured by any phase.
     * 
     * @param scenarioDirectory - The directory the scenario is running from.
     * @throws ConfigManagerException - If setup failed
     */
    public abstract void setup(String scenarioDirectory) throws ConfigManagerException;

    /**
     * Apply the given phase to the given host
     * 
     * @param label The label of the host to apply the phase to
     * @param phase The phase to apply
     * @param poweroff Whether or not the VM should poweroff after
     * @throws ConfigManagerException If unable to apply the requested phase
     */
    public abstract void doPhase(String label, String phase, boolean poweroff) throws ConfigManagerException;

    /**
     * Do whatever is necessary for the configuration management system to clean
     * up. This is intended to be called when no more machines will be
     * configured. For example when every machine has finished all required
     * phases or an exceptional condition occurred where no more machines can be
     * configured.
     * 
     * @throws ConfigManagerException If unable to clean up
     */
    public abstract void cleanUp() throws ConfigManagerException;

    /**
     * Get the OccpHost with the given label
     * 
     * @param label the label of the host desired
     * @return The host if found, null otherwise
     */
    protected OccpHost getHostByLabel(String label) {
        OccpHost result = null;

        FINDHOST: for (OccpHost currentHost : this.hosts) {
            if (currentHost.getLabel().equalsIgnoreCase(label)) {
                result = currentHost;
                break FINDHOST;
            }
        }

        return result;
    }
}
