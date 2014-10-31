package edu.uri.dfcsc.occp;

import java.util.Enumeration;
import java.util.Hashtable;

/**
 * Data structure for OCCP Content Packs
 */
public class ContentPackInfo implements Cloneable {
    private final String packName, classToInclude;

    // Stores any optional parameters we encounter
    private final Hashtable<String, String> customParameters = new Hashtable<String, String>();

    // Keeps track of the longest parameter name for later formatting
    private int longestParameterName = 0;

    /**
     * Construct using the default "all" class
     * 
     * @param packName Name of the pack
     */
    public ContentPackInfo(String packName) {
        this(packName, "all");
    }

    /**
     * Construct with the pack name and class to include in the node definition
     * 
     * @param packName Name of the pack
     * @param className Name of the class to use
     */
    public ContentPackInfo(String packName, String className) {
        this.packName = packName;
        this.classToInclude = className;
    }

    // Setters & Getters ---------------------------------------------------

    // Getters
    /**
     * @return The name of the class this content pack uses
     */
    public String getClassName() {
        return this.classToInclude;
    }

    /**
     * @return The hash table of parameter names and their values
     */
    @SuppressWarnings("unchecked")
    public Hashtable<String, String> getCustomParameters() {
        return ((Hashtable<String, String>) this.customParameters.clone());
    }

    /**
     * @return The length of the longest parameter name
     */
    public int getLongestParameterNameLength() {
        return this.longestParameterName;
    }

    /**
     * @return The number of custom parameters this pack has received
     */
    public int getNumberOfCustomParameters() {
        return this.customParameters.size();
    }

    /**
     * @return The name of the content pack
     */
    public String getPackName() {
        return this.packName;
    }

    // Setters

    // Classes & Types -----------------------------------------------------

    // Utility Methods -----------------------------------------------------
    /**
     * Add a custom parameter to use with this content pack
     * 
     * @param parameterName The name of the parameter
     * @param parameterValue The parameter value
     */
    public void addCustomParameter(String parameterName, String parameterValue) {
        // Store the pair
        this.customParameters.put(parameterName, parameterValue);
        // Determine if this is the longest parameter we've encountered so far
        // and update if needed
        int parameterLength = parameterName.length();
        if (parameterLength > this.longestParameterName) {
            // This is now the longest parameter name we've encountered
            this.longestParameterName = parameterLength;
        }
    }

    @Override
    public ContentPackInfo clone() {
        ContentPackInfo result = new ContentPackInfo(this.getPackName(), this.getClassName());
        if (this.hasCustomParameter()) {
            Enumeration<String> parameters = this.getCustomParameters().keys();
            while (parameters.hasMoreElements()) {
                String parameter = parameters.nextElement();
                result.addCustomParameter(parameter, this.getCustomParameters().get(parameter));
            }
        }

        return result;
    }

    /**
     * Determine if there were any custom parameters
     * 
     * @return Returns true if there are one or more custom parameters &amp; false
     *         otherwise
     */
    public boolean hasCustomParameter() {
        // While one could use longestParameterName_ to determine
        // this, that was meant for pretty printing. Asking the
        // structure is probably a safer determination
        return !this.customParameters.isEmpty();
    }
}
