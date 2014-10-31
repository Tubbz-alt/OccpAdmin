package edu.uri.dfcsc.occp.generator;

import java.util.ArrayList;
import java.util.HashMap;

import edu.uri.dfcsc.occp.OccpParser.OccpVariables;
import edu.uri.dfcsc.occp.exceptions.OccpException;

/**
 * All OCCP Generators should extend and implement this class
 */
public abstract class Generator {

    /**
     * The implementing child will generate what ever variables are appropriate for its generation based on the
     * specified variable name and parameters.
     * 
     * @param variableName - The variable name the generator will generator or base off of
     * @param parameters - The parameters that the generator will use
     * @return A HashMap of the variables it has generated
     * @throws InvalidGeneratorParameterException
     */
    public abstract OccpVariables generate(String variableName, HashMap<String, String> parameters)
            throws InvalidGeneratorParameterException;

    /**
     * Attempts to get the string value from the parameter
     * 
     * @param paramName - The parameter to consider
     * @param parameters - The map of the parameters
     * @param defaultValue - The default value
     * @param required - Whether it was a required param or not
     * @return - The value or the default if not found
     * @throws InvalidGeneratorParameterException if missing but required
     */
    protected String getStringValue(String paramName, HashMap<String, String> parameters, String defaultValue,
            boolean required) throws InvalidGeneratorParameterException {
        String result = defaultValue;

        if (parameters.containsKey(paramName)) {
            // Parameter found and is already a string so return it as is
            result = parameters.get(paramName);
        } else {
            if (required) {
                // Not found but required
                throw new InvalidGeneratorParameterException(getGeneratorName() + " requires the parameter \""
                        + paramName + "\" but was not given it.");
            }
        }
        return result;
    }

    /**
     * Attempts to get the string value from the parameter but it must be one of the given options
     * 
     * @param paramName - The parameter to consider
     * @param parameters - The map of the parameters
     * @param options - A list of valid options, the value must be one of these.
     * @param defaultValue - The default value
     * @param required - Whether it was a required param or not
     * @return - The value or the default if not found
     * @throws InvalidGeneratorParameterException if missing but required or did not match any of the options
     */
    protected String getStringValue(String paramName, HashMap<String, String> parameters, ArrayList<String> options,
            String defaultValue, boolean required) throws InvalidGeneratorParameterException {
        String result = defaultValue;

        if (parameters.containsKey(paramName)) {
            // Parameter found and is already a string
            result = parameters.get(paramName);

            // But does this match the possible options?
            if (!options.contains(result)) {
                // It did not match
                throw new InvalidGeneratorParameterException(getGeneratorName() + " did not expect the value \""
                        + result + "\"for the parameter \"" + paramName);
            }
        } else {
            if (required) {
                // Not found but required
                throw new InvalidGeneratorParameterException(getGeneratorName() + " requires the parameter \""
                        + paramName + "\" but was not given it.");
            }
        }
        return result;
    }

    /**
     * Attempts to get int value from the parameter
     * 
     * @param paramName - The parameter to consider
     * @param parameters - The map of the parameters
     * @param defaultValue - The default value
     * @param required - Whether it was a required param or not
     * @return - The value or the default if not found
     * @throws InvalidGeneratorParameterException if missing but required or provided but invalid form
     */
    protected int getIntValue(String paramName, HashMap<String, String> parameters, int defaultValue, boolean required)
            throws InvalidGeneratorParameterException {
        int result = defaultValue;

        if (parameters.containsKey(paramName)) {
            try {
                // Found it but we need to convert to an int
                result = Integer.parseInt(parameters.get(paramName));
            } catch (NumberFormatException e) {
                // Unable to convert to int
                throw new InvalidGeneratorParameterException(getGeneratorName() + " requires the parameter \""
                        + paramName + "\" to be an integer but was given \"" + parameters.get(paramName) + "\"");
            }
        } else {
            if (required) {
                // Missing but required
                throw new InvalidGeneratorParameterException(getGeneratorName() + " requires the parameter \""
                        + paramName + "\" but was not given it.");
            }
        }
        return result;
    }

    /**
     * Attempts to pull out the range from the parameter.
     * 
     * @param paramName - The parameter to consider
     * @param parameters - The map of the parameters
     * @param defaultMin - Default minimum to use
     * @param defaultMax - Default maximum to use
     * @param required - Whether it was a required param or not
     * @return an array containing the min value followed by the max value
     * @throws InvalidGeneratorParameterException if missing but required or provided but invalid form
     */
    protected int[] getRangeValue(String paramName, HashMap<String, String> parameters, int defaultMin, int defaultMax,
            boolean required) throws InvalidGeneratorParameterException {
        int[] result = new int[2];
        result[0] = defaultMin;
        result[1] = defaultMax;

        if (parameters.containsKey(paramName)) {
            // Parameter found
            String paramValue = parameters.get(paramName);
            if (paramValue.contains(":")) {
                // Potentially of the form "min:max""
                try {
                    // Attempt to convert min and max to ints
                    result[0] = Integer.parseInt(paramValue.substring(0, paramValue.indexOf(':')));
                    result[1] = Integer
                            .parseInt(paramValue.substring(paramValue.indexOf(':') + 1, paramValue.length()));

                    // If we were actually given max:min instead of min:max we should just accept it. Some generator may
                    // want this behavior

                } catch (NumberFormatException e) {
                    // At least one of min or max was not an int
                    throw new InvalidGeneratorParameterException("Parameter \"" + paramName
                            + "\" encountered an improperly formatted range: " + paramValue + " expecting MIN:MAX");
                }
            } else {
                // Could not possibly be of the min:max form, this cannot be a proper range
                throw new InvalidGeneratorParameterException("Parameter \"" + paramName
                        + "\" encountered an improperly formatted range: " + paramValue + " expecting MIN:MAX");
            }
        } else {
            if (required) {
                // Missing but required
                throw new InvalidGeneratorParameterException(getGeneratorName() + " requires the parameter \""
                        + paramName + "\" but was not given it.");
            }
        }
        return result;
    }

    /**
     * Attempts to get boolean value from the parameter. Warning: this uses Boolean.parseBoolen(String s) which
     * "The boolean returned represents the value true if the string argument is not null and is equal, ignoring case, to the string "
     * true"."
     * 
     * @param paramName - The parameter to consider
     * @param parameters - The map of the parameters
     * @param defaultValue - The default value
     * @param required - Whether it was a required param or not
     * @return - The value or the default if not found
     * @throws InvalidGeneratorParameterException if missing but required
     */
    protected boolean getBooleanValue(String paramName, HashMap<String, String> parameters, boolean defaultValue,
            boolean required) throws InvalidGeneratorParameterException {
        boolean result = defaultValue;

        if (parameters.containsKey(paramName)) {
            // Found it, convert to boolean
            result = Boolean.parseBoolean(parameters.get(paramName));
        } else {
            if (required) {
                // Missing but required
                throw new InvalidGeneratorParameterException(getGeneratorName() + " requires the parameter \""
                        + paramName + "\" but was not given it.");
            }
        }
        return result;
    }

    /**
     * Many generators will likely support the count parameter. This will return 1 if the parameter is not provided and
     * provide sanity checking if it is provided.
     * 
     * @param parameters - The map of parameters
     * @return The value of the provided parameter if &gt;0 or 1 if the parameter was not provided
     * @throws InvalidGeneratorParameterException - Thrown if the given parameter could not be parsed to an integer &gt;
     *             0
     */
    protected int getCount(HashMap<String, String> parameters) throws InvalidGeneratorParameterException {
        int result = 1; // Default option
        if (parameters.containsKey("count")) {
            // May have been provided but is it valid?
            result = this.getIntValue("count", parameters, 0, false);
            if (result < 1) {
                // Invalid
                throw new InvalidGeneratorParameterException(
                        "Parameter \"count\" expected a positive non-zero number but got: " + result);
            }
        }
        return result;
    }

    /**
     * Returns the name of the generator
     * 
     * @return String name of the generator
     */
    public abstract String getGeneratorName();

    /**
     * Exception that would indicate either a missing but required parameter or a parameter that was not of the type
     * expected
     */
    public static class InvalidGeneratorParameterException extends OccpException {
        private static final long serialVersionUID = 1L;

        /**
         * Constructor that sets the Exception's message
         * 
         * @param message - The exception message
         */
        public InvalidGeneratorParameterException(final String message) {
            super(message);
        }

        /**
         * Constructor that sets the Exception's message
         * 
         * @param message - The exception message
         * @param e - Causing exception
         */
        public InvalidGeneratorParameterException(final String message, Throwable e) {
            super(message, e);
        }
    }
}
