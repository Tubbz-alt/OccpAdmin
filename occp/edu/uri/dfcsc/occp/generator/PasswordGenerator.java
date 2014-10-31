package edu.uri.dfcsc.occp.generator;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;

import org.apache.commons.codec.digest.Crypt;
import org.apache.commons.lang.RandomStringUtils;

import edu.uri.dfcsc.occp.OccpAdmin;
import edu.uri.dfcsc.occp.OccpParser.OccpVariables;

/**
 * Work in progress - Used to generate passwords
 * Accepted parameters:
 * algorithm - One of MD5, SHA256, SHA512 to use as the hashing algorithm for the password. Default is SHA512 (Optional)
 * count - A positive non-zero integer representing the number of passwords to generate. Default is 1 (Optional)
 * length - A positive non-zero integer for the length of generated password to be or a range in the form min:max where
 * the length is randomly picked from that range with the bounds included as possibilities. (Required if password or
 * pool not specified)
 * password - A password to use. (Useful if you are interested in the shadow line). All other parameters are ignored.
 * (Optional)
 * pool - A file located in the scenario base directory to use as pool of passwords to choose from. The type and length
 * parameters will be ignored (Optional)
 * (Optional)
 * type - One of Alpha, AlphaNumeric, ASCII. This determines acceptable characters to make up the password with. Default
 * is AlphaNumeric (Optional)
 */
public class PasswordGenerator extends Generator {
    private int minLength = 0, maxLength = 0;
    private String type;
    private Stack<String> pool;
    EncryptionType encryptionType;

    @Override
    public OccpVariables generate(String variableName, HashMap<String, String> parameters)
            throws InvalidGeneratorParameterException {
        OccpVariables result = new OccpVariables();

        String password = this.getStringValue("password", parameters, null, false);

        // Determine the possible algorithms
        ArrayList<String> algorithmOptions = new ArrayList<>();
        algorithmOptions.add("MD5");
        algorithmOptions.add("SHA256");
        algorithmOptions.add("SHA512");
        encryptionType = EncryptionType.valueOf(this.getStringValue("algorithm", parameters, algorithmOptions,
                "SHA512", false));

        if (password == null) {
            // We need to generate the plain text password and shadow parts

            // Get the count
            int count = getCount(parameters);

            // Check if a pool file was specified
            String poolFilename = this.getStringValue("pool", parameters, "", false);
            if (!poolFilename.equalsIgnoreCase("")) {
                // There was one, try to set the pool
                this.setPool(poolFilename);
                type = "FromPool";
            } else {
                // Get the length
                int length = 0;
                if (parameters.containsKey("length")) {

                    String lengthParam = parameters.get("length");

                    if (lengthParam.contains(":")) {
                        // Range specified
                        int[] range = this.getRangeValue("length", parameters, 0, 0, true);
                        this.minLength = range[0];
                        this.maxLength = range[1];

                        if (this.minLength > this.maxLength) {
                            throw new InvalidGeneratorParameterException(
                                    "Parameter \"length\" reccived a range but the min was greater than the max");
                        }

                    } else {
                        // simple number specified
                        length = this.getIntValue("length", parameters, 1, true);
                        this.minLength = length;
                        this.maxLength = this.minLength;
                        if (length < 1) {
                            throw new InvalidGeneratorParameterException(
                                    "Parameter \"length\" expected a positive non-zero number but got: " + length);
                        }
                    }
                } else {
                    throw new InvalidGeneratorParameterException("Parameter \"length\" is required for this generator");
                }
            }

            // Determine the possible characters
            ArrayList<String> characterSetOptions = new ArrayList<>();
            characterSetOptions.add("Alpha");
            characterSetOptions.add("AlphaNumeric");
            characterSetOptions.add("ASCII");

            if (type == null) {
                type = this.getStringValue("type", parameters, characterSetOptions, "AlphaNumeric", false);
            }
            if (count > 1) {
                // More than one requested, index
                ArrayList<String> plain = new ArrayList<>();
                ArrayList<String> shadow = new ArrayList<>();
                for (int i = 1; i <= count; i++) {

                    // Generate the plaintext version
                    String generatedPlaintext = makePlainText();

                    // With plaintext version generate shadow file password
                    String generatedShadowLine = generatePasswd(generatedPlaintext);

                    // Store the generated material
                    plain.add(generatedPlaintext);
                    shadow.add(generatedShadowLine);
                }
                result.setVariable(variableName + "_plain", plain);
                result.setVariable(variableName + "_shadow", shadow);
            } else {
                // Only one requested, do not index

                // Generate the plaintext version
                String generatedPlaintext = makePlainText();

                // With plaintext version generate shadow file password
                String generatedShadowLine = generatePasswd(generatedPlaintext);

                // Store the generated material
                result.setVariable(variableName + "_plain", generatedPlaintext);
                result.setVariable(variableName + "_shadow", generatedShadowLine);
            }
        } else {
            // We are not being asked to generate the plain text so just make the shadow file part
            result.setVariable(variableName + "_plain", password);
            result.setVariable(variableName + "_shadow", generatePasswd(password));
        }

        return result;
    }

    /**
     * Makes the plain text password
     * 
     * @return the plain text password
     * @throws InvalidGeneratorParameterException - Pool is empty
     */
    private String makePlainText() throws InvalidGeneratorParameterException {
        String generatedPlaintext = "";
        if (type.equals("AlphaNumeric")) {
            generatedPlaintext = generateRandomAlphaNum(this.pickLength());
        } else if (type.equals("Alpha")) {
            generatedPlaintext = generateRandomAlpha(this.pickLength());
        } else if (type.equals("ASCII")) {
            generatedPlaintext = generateRandomAscii(this.pickLength());
        } else if (type.equals("FromPool")) {
            // Grab one from the pool
            try {
                generatedPlaintext = this.pool.pop();
            } catch (EmptyStackException exception) {
                // The pool was exhausted prematurely
                throw new InvalidGeneratorParameterException(
                        "There were not enough passwords in the pool file to satisfy amount requested from the generator");
            }
        }
        return generatedPlaintext;
    }

    /**
     * Generate a random Alphabetic string.
     * Note: For scenario convenience only! Do not use for production passwords!
     * 
     * @param length the length of the string to be generated
     * @return the generated string
     */
    private String generateRandomAlpha(int length) {
        return RandomStringUtils.randomAlphabetic(length);
    }

    /**
     * Generate a random Alphabetic string.
     * Note: For scenario convenience only! Do not use for production passwords!
     * 
     * @param length the length of the string to be generated
     * @return the generated string
     */
    private String generateRandomAlphaNum(int length) {
        return RandomStringUtils.randomAlphanumeric(length);
    }

    /**
     * Generate a random string from ASCII values between 32 and 126
     * (inclusive).
     * Note: For scenario convenience only! Do not use for production passwords!
     * 
     * @param length the length of the string to be generated
     * @return the generated string
     */
    private String generateRandomAscii(int length) {
        return RandomStringUtils.randomAscii(length);
    }

    /**
     * Generate a random string from the given char pool
     * 
     * @param length the length of the string to be generated
     * @param pool a char array of possible characters
     * @return the generated string
     */
    @SuppressWarnings("unused")
    private String generateRandom(final int length, final char[] pool) {
        return RandomStringUtils.random(length, pool);
    }

    /**
     * If not using a range the length picked is the length specified by the length parameter. If it is a range a length
     * will be picked at random from it
     * 
     * @return - a length
     */
    private int pickLength() {
        int result = this.minLength;

        if (this.minLength != this.maxLength) {
            Random rnd = new Random();
            result = rnd.nextInt(this.maxLength - this.minLength + 1) + this.minLength;
        }

        return result;
    }

    /**
     * Generate password for puppet from the plaintext using SHA-512
     * 
     * @param ptPasswd the plaintext version of the password
     * @return Appropriate password string for use with puppet
     */
    private String generatePasswd(final String ptPasswd) {

        return generatePasswd(ptPasswd, this.encryptionType);
    }

    /**
     * Generate password for puppet from the plaintext using specified algorithm
     * 
     * @param ptPasswd the plaintext version of the password
     * @param encryptionType the algorithm to use
     * @return Appropriate password string for use with puppet
     */
    private String generatePasswd(String ptPasswd, EncryptionType encryptionType) {
        String ctPasswd = null, algPrefix = getAlgorithmPrefix(encryptionType);

        if (algPrefix != null) {
            ctPasswd = Crypt.crypt(ptPasswd, algPrefix + makeSalt());
        }

        return ctPasswd;
    }

    /**
     * Get the algorithm prefix for the shadow line
     * 
     * @param encryptionType - the encryption type
     * @return The correct prefix or null
     */
    private String getAlgorithmPrefix(EncryptionType encryptionType) {
        final String result;
        // Referenced http://manpages.courier-mta.org/htmlman3/crypt.3.html
        // $id$salt$encrypted
        // ID Method
        // 1 MD5
        // 5 SHA-256 (since glibc 2.7)
        // 6 SHA-512 (since glibc 2.7)
        switch (encryptionType) {
        case MD5:
            result = "$1$";
            break;
        case SHA256:
            result = "$5$";
            break;
        case SHA512:
            result = "$6$";
            break;
        default:
            // Not much we can do, caller should check for null string
            result = null;
            break;
        }
        return result;
    }

    /**
     * Makes a salt with a default length of 8
     * 
     * @return the salt string
     */
    private String makeSalt() {
        return makeSalt(8);
    }

    /**
     * Makes a salt with the given length
     * 
     * @param length - The length of the salt string
     * @return the salt string
     */
    private String makeSalt(int length) {
        char[] acceptableChars = { 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q',
                'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K',
                'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '0', '1', '2', '3', '4',
                '5', '6', '7', '8', '9', '.', '/' };
        try {
            return RandomStringUtils.random(length, 0, acceptableChars.length, false, false, acceptableChars,
                    SecureRandom.getInstance("SHA1PRNG"));
        } catch (NoSuchAlgorithmException e) {
            // Well we tried to be somewhat secure but couldn't
            // falling back
            return RandomStringUtils.random(length, 0, acceptableChars.length, false, false, acceptableChars);
        }
    }

    /**
     * A representation of the encryption algorithm to be used
     */
    public enum EncryptionType {
        /**
         * MD5 Algorithm
         */
        MD5,
        /**
         * SHA-256 Algorithm
         */
        SHA256,
        /**
         * SHA-512 Algorithm
         */
        SHA512,
    }

    /**
     * Reads in the pool file and shuffles it to create a pool of plain text passwords to later choose from.
     * 
     * @param poolFilename - The name of the pool file
     * @throws InvalidGeneratorParameterException - If there is a problem with the pool file.
     */
    private void setPool(String poolFilename) throws InvalidGeneratorParameterException {
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(OccpAdmin.scenarioBaseDir + "/"
                + poolFilename))) {
            // Temporary String to hold each line of the file as it is read
            String line;
            // Initialize the pool
            pool = new Stack<>();
            // Read the file line by line
            while ((line = bufferedReader.readLine()) != null) {
                // Add each line to the pool
                pool.add(line);
            }
            // Randomize the order of the pool
            Collections.shuffle(pool);
        } catch (IOException exception) {
            // Problem with the pool file
            throw new InvalidGeneratorParameterException("Could not open pool file: \"" + poolFilename + '"', exception);
        }
    }

    @Override
    public String getGeneratorName() {
        return "password";
    }
}
