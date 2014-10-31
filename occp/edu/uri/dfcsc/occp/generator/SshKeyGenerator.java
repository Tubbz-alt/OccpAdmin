package edu.uri.dfcsc.occp.generator;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;

import edu.uri.dfcsc.occp.OccpParser.OccpVariables;

/**
 * Work in progress - Used to generate ssh key pairs
 * Accepted parameters:
 * count - A positive non-zero integer representing the number of passwords to generate. Default is 1. (Optional)
 * password - A String password to encrypt the key (Optional)
 * Possibly TODO: Accept other algorithms for key generation
 */
public class SshKeyGenerator extends Generator {

    @Override
    public OccpVariables generate(String variableName, HashMap<String, String> parameters)
            throws InvalidGeneratorParameterException {

        OccpVariables result = new OccpVariables();

        // Get the count
        int count = getCount(parameters);

        // Get optional password for key
        String password = this.getStringValue("password", parameters, null, false);
        if (count > 1) {
            ArrayList<String> privateKeys = new ArrayList<>();
            ArrayList<String> publicKeys = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                SshKeyPair keyPair = generateRSA(password);
                // Store the generated material
                privateKeys.add(keyPair.getPrivateKey());
                publicKeys.add(keyPair.publicKey);
            }
            result.setVariable(variableName + "_private", privateKeys);
            result.setVariable(variableName + "_public", publicKeys);
        } else {
            SshKeyPair keyPair = generateRSA(password);
            // Store the generated material
            result.setVariable(variableName + "_private", keyPair.getPrivateKey());
            result.setVariable(variableName + "_public", keyPair.publicKey);
        }

        return result;
    }

    /**
     * Generate a RSA keypair with no pass phrase
     * 
     * @return OCCPSSHKeyPair or null if unsuccessful
     */
    @SuppressWarnings("unused")
    private SshKeyPair generateRSA() {
        return generateRSA(null);
    }

    /**
     * Generate a RSA keypair with given pass phrase
     * 
     * @param passphrase
     * @return OCCPSSHKeyPair or null if unsuccessful
     */
    private SshKeyPair generateRSA(String passphrase) {
        return generateKey(passphrase, KeyPair.RSA);
    }

    /**
     * Generate the key pair with the given algorithm possibly encrypting with a pass phrase
     * 
     * @param passphrase - A pass phrase to encrypt the key with or null/empty string if encryption is not requested
     * @param algorithm - which algorithm should be used to generate the keys
     * @return - The key pair
     */
    private SshKeyPair generateKey(String passphrase, int algorithm) {
        SshKeyPair result = null;
        final JSch jsch = new JSch();
        try {
            // We will use this to capture the keys to a string instead of
            // writing to a file because a string is more useful to us
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

            // Generate a key pair via JSCH
            KeyPair keyPair = KeyPair.genKeyPair(jsch, algorithm);

            // Capture private key, provide pass phrase if provided to us
            if (passphrase == null || passphrase.equals("")) {
                keyPair.writePrivateKey(byteArrayOutputStream);
            } else {
                keyPair.writePrivateKey(byteArrayOutputStream, passphrase.getBytes());
            }
            String privateKey = byteArrayOutputStream.toString();

            // Capture public key
            byteArrayOutputStream.reset();
            keyPair.writePublicKey(byteArrayOutputStream, "");
            String publicKey = byteArrayOutputStream.toString();

            result = new SshKeyPair(publicKey, privateKey);

            // We've got our keys, clean up
            keyPair.dispose();
            byteArrayOutputStream.reset();

        } catch (JSchException e) {
            // There isn't much we can do recover caller will get null
        }
        return result;
    }

    /**
     * Data structure to hold public and private SSH key parts
     */
    private static final class SshKeyPair {
        private final String publicKey, privateKey;

        public SshKeyPair(String publicKey, String privateKey) {
            this.publicKey = publicKey;
            this.privateKey = privateKey;
        }

        public String getPublicKey() {
            return this.publicKey;
        }

        public String getPrivateKey() {
            return this.privateKey;
        }

        @Override
        public String toString() {
            return this.getPublicKey() + "\n" + this.getPrivateKey();
        }
    };

    @Override
    public String getGeneratorName() {
        return "ssh_key";
    }
}
