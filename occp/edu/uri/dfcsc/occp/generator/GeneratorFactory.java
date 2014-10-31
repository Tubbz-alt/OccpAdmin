package edu.uri.dfcsc.occp.generator;

/**
 * Factory for Generator objects
 */
public class GeneratorFactory {

    /**
     * Returns the Generator specified
     * 
     * @param generatorName - The Generator being requested
     * @return - The requested generator
     * @throws UnknownGeneratorException If the requested Generator is unknown to the factory
     */
    public Generator getGenerator(final String generatorName) throws UnknownGeneratorException {
        Generator result = null;

        if (generatorName.equals("password")) {
            result = new PasswordGenerator();
        } else if (generatorName.equals("ssh_key")) {
            result = new SshKeyGenerator();
        } else if (generatorName.equals("username")) {
            result = new UsernameGenerator();
        } else if (generatorName.equals("random")) {
            result = new RandomNumberGenerator();
        } else {
            throw new UnknownGeneratorException("Unknown generator \"" + generatorName + "\" requested");
        }

        return result;
    }

    /**
     * Thrown if the requested generator is unknown to this factory
     */
    public static class UnknownGeneratorException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * Default constructor
         */
        public UnknownGeneratorException() {
            super("");
        }

        /**
         * Simple constructor which passes the given message to the parent class
         * 
         * @param message - The message for this Exception
         */
        public UnknownGeneratorException(final String message) {
            super(message);
        }
    }
}
