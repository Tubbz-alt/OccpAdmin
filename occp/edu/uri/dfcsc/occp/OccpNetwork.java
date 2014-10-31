package edu.uri.dfcsc.occp;

/**
 * Data structure to hold information about a hypervisor network, a bit sparse for now
 */
public class OccpNetwork implements Comparable<OccpNetwork> {
    private String label;

    /**
     * @return The network's label/name
     */
    public String getLabel() {
        return this.label;
    }

    /**
     * @param label The label to set to
     */
    public void setLabel(final String label) {
        this.label = label;
        if (OccpAdmin.instanceId != null && !label.equals(OccpParser.INTERNET_NAME)) {
            this.label += "-" + OccpAdmin.instanceId;
        }
    }

    @Override
    public int hashCode() {
        return label.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof OccpNetwork) {
            return label.equals(((OccpNetwork) o).label);
        }
        return false;
    }

    @Override
    public int compareTo(OccpNetwork o) {
        return this.label.compareTo(o.label);
    }
}
