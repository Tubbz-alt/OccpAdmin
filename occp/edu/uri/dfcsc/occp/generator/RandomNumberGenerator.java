/**
 * 
 */
package edu.uri.dfcsc.occp.generator;

import java.util.HashMap;
import java.util.Random;

import edu.uri.dfcsc.occp.OccpParser.OccpVariables;

/**
 * Generates a random number between parameters min and max
 * 
 * @author Kevin Bryan (bryank@cs.uri.edu)
 */
public class RandomNumberGenerator extends Generator {

    @Override
    public OccpVariables generate(String variableName, HashMap<String, String> parameters)
            throws InvalidGeneratorParameterException {
        OccpVariables result = new OccpVariables();
        int rangeBottom = 0, rangeTop = Integer.MAX_VALUE;
        if (!parameters.containsKey("max")) {
            throw new InvalidGeneratorParameterException("Missing \"max\" parameter");
        }
        try {
            rangeTop = Integer.parseInt(parameters.get("max"));
            if (parameters.containsKey("min")) {
                rangeBottom = Integer.parseInt(parameters.get("min"));
            }
            if (rangeTop < rangeBottom) {
                InvalidGeneratorParameterException e = new InvalidGeneratorParameterException("max < min");
                e.set("min", rangeBottom).set("max", rangeTop);
                throw e;
            }
            rangeTop = Integer.parseInt(parameters.get("max"));
            Long fullRange = Long.valueOf(rangeTop) - Long.valueOf(rangeBottom) + 1;
            Random randGen = new java.util.Random();
            long value;
            if (fullRange > Integer.MAX_VALUE) {
                value = randGen.nextLong() % fullRange;
                value += rangeBottom;
            } else {
                value = rangeBottom + randGen.nextInt(fullRange.intValue());
            }
            result.setVariable(variableName, Long.toString(value));
        } catch (NumberFormatException e) {
            throw new InvalidGeneratorParameterException("Invalid format: " + e.getLocalizedMessage(), e);
        }
        return result;
    }

    @Override
    public String getGeneratorName() {
        return "random";
    }

}
