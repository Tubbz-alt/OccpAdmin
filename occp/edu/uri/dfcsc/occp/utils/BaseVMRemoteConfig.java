package edu.uri.dfcsc.occp.utils;

import java.io.*;

import com.jcraft.jsch.*;

import edu.uri.dfcsc.occp.exceptions.configmanager.ConfigManagerException;
import edu.uri.dfcsc.occp.exceptions.configmanager.ConfigManagerPermanentFailureException;
import edu.uri.dfcsc.occp.exceptions.configmanager.ConfigManagerTemporaryFailureException;

/**
 * Class to help run commands on remote machines
 */
public class BaseVMRemoteConfig {

    private final JSch jsch = new JSch();

    private AuthenticationMethod authenticationMethod = AuthenticationMethod.NOT_CONFIGURED;

    private RemoteUserInfo userInfo = null;

    private String address = null;

    private String username = null;

    private String keyComment = null;

    /**
     * This constructor will use the default AdminVM public and private key as the authentication method for the given
     * username on the given host
     * 
     * @param username username to use for ssh connection
     * @param address address to use for ssh connection (ipv4 address)
     * @throws ConfigManagerPermanentFailureException If there was an issue with the ssh keys
     */
    public BaseVMRemoteConfig(String username, String address) throws ConfigManagerPermanentFailureException {
        this.setUsername(username);
        this.setAddress(address);
        if (!this.setKey()) {
            throw new ConfigManagerPermanentFailureException("Could not set the default AdminVM keys");
        }

        this.setAuthenticationMethod(BaseVMRemoteConfig.AuthenticationMethod.DEFAULT_ADMINVM_KEY);

    }

    /**
     * This constructor will use the given public and private key as the authentication method for the given username on
     * the given host. If the private key is not encrypted a null string should be provided as the password
     * 
     * @param username username to use for ssh connection
     * @param address address to use for ssh connection (ipv4 address)
     * @param privateKeyFile the path to the private key. It is assumed that the public key is found by adding .pub
     * @param password a null string for plain text keys, the password for encrypted keys
     * @param keyComment will be used to remove the key if destroyRemoteAccess() is called
     * @throws ConfigManagerPermanentFailureException If there was an issue with the ssh keys
     */
    public BaseVMRemoteConfig(String username, String address, String privateKeyFile, String password, String keyComment)
            throws ConfigManagerPermanentFailureException {
        this.setUsername(username);
        this.setAddress(address);
        if (!this.setKey(privateKeyFile, password, keyComment)) {
            throw new ConfigManagerPermanentFailureException("Could not set the custom keys");
        }

        this.setAuthenticationMethod(BaseVMRemoteConfig.AuthenticationMethod.CUSTOM_KEY);
    }

    /**
     * This constructor will use password based authentication for the given host as the given user
     * 
     * @param username username to use for ssh connection
     * @param address address to use for ssh connection (ipv4 address)
     * @param password password for the user
     */
    public BaseVMRemoteConfig(String username, String address, String password) {
        this.setAuthenticationMethod(BaseVMRemoteConfig.AuthenticationMethod.PASSWORD);
        this.setUsername(username);
        this.setAddress(address);
        BaseVMRemoteConfig.RemoteUserInfo userInfo = new BaseVMRemoteConfig.RemoteUserInfo(password);
        this.setUserInfo(userInfo);
    }

    // Setters & Getters ---------------------------------------------------

    // Getters
    /**
     * @return The IP address
     */
    public String getAddress() {
        return this.address;
    }

    /**
     * @return The AuthenticationMethod enum
     */
    public AuthenticationMethod getAuthenticationMethod() {
        return this.authenticationMethod;
    }

    /**
     * @return The username used to connect with
     */
    public String getUsername() {
        return this.username;
    }

    // Setters

    // Classes & Types -----------------------------------------------------
    /**
     * Used to keep track of how our SSH session will authenticate
     */
    public static enum AuthenticationMethod {
        /**
         * Default AdminVM private/public key
         */
        DEFAULT_ADMINVM_KEY,
        /**
         * Custom public/private key
         */
        CUSTOM_KEY,
        /**
         * Password based authentication
         */
        PASSWORD,
        /**
         * Invalid state
         */
        NOT_CONFIGURED,
        /**
         * authentication method was removed on remote
         */
        DESTROYED;
    }

    /**
     * Storage for various artifacts from running a command.
     */
    public static class CommandOutput {
        private final int exitStatus;
        private final String command, output, errorOutput;

        /**
         * Default constructor, not to be used
         */
        public CommandOutput() {
            this(-1, "", "", "");
        }

        /**
         * Constructs the object filling all the elements
         * 
         * @param exitStatus Command's exit status
         * @param output The standard output of the command (if any)
         * @param errorOutput The error output of the command (if any)
         * @param command A representation of the command run mostly as a reference, one should not rely on running this
         *            string as a command
         */
        public CommandOutput(int exitStatus, String output, String errorOutput, String command) {
            this.exitStatus = exitStatus;
            this.output = output;
            this.errorOutput = errorOutput;
            this.command = command;
        }

        /**
         * Get the command that was stored in this object, runScript will give an approximate command for what it had to
         * do behind the scenes. This is really only for reference and completeness.
         * 
         * @return The command
         */
        public String getCommand() {
            return command;
        }

        /**
         * Get the string we built from the error stream
         * 
         * @return the error output
         */
        public String getErrorOutput() {
            return errorOutput;
        }

        /**
         * Get the exit status of the command
         * 
         * @return exit status
         */
        public int getExitStatus() {
            return exitStatus;
        }

        /**
         * Get the standard output from the command
         * 
         * @return the standard output
         */
        public String getOutput() {
            return output;
        }

        @Override
        public String toString() {
            return "Command: " + getCommand() + "\nExit: " + getExitStatus() + "\nOutput Stream: " + getOutput()
                    + "\nError Stream: " + getErrorOutput();
        }
    }

    /**
     * Implements JSch UserInfo and is used internally for "keyboard" authentication, however in our use case the
     * password is supplied programatically
     */
    private static class RemoteUserInfo implements UserInfo {

        private final String password;

        public RemoteUserInfo(String password) {
            this.password = password;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        // The only method that really matters for our purposes
        @Override
        public String getPassword() {
            return this.password;
        }

        // Required to implement UserInfo, but are unneeded by us instead we will always report success should any of
        // these methods be called
        @Override
        public boolean promptPassphrase(String arg0) {
            return true;
        }

        @Override
        public boolean promptPassword(String arg0) {
            return true;
        }

        @Override
        public boolean promptYesNo(String arg0) {
            return true;
        }

        @Override
        public void showMessage(String arg0) {
        }

    }

    // Utility Methods -----------------------------------------------------

    /**
     * Attempt to wait for the given time
     * 
     * @param wait time to wait
     */
    private void attemptWait(int wait) {
        try {
            Thread.sleep(wait);
        } catch (Exception e) {
        }
    }

    @SuppressWarnings("unused")
    private String getKeyComment() {
        String result = "";
        if (this.getAuthenticationMethod() == BaseVMRemoteConfig.AuthenticationMethod.DEFAULT_ADMINVM_KEY
                || this.getAuthenticationMethod() == BaseVMRemoteConfig.AuthenticationMethod.CUSTOM_KEY) {
            result = this.keyComment;
        }
        return result;
    }

    private BaseVMRemoteConfig.RemoteUserInfo getUserInfo() {
        return this.userInfo;
    }

    private void handleJSchException(JSchException exception) throws ConfigManagerException {
        String message = exception.getMessage();

        if (message.equalsIgnoreCase("java.net.NoRouteToHostException: No route to host")
                || message.contains("Connection refused") || message.contains("Connection timed out")
                || message.contains("timeout") || message.contains("session is down")
                || message.contains("End of IO Stream Read")) {
            // Networking issue
            throw new ConfigManagerTemporaryFailureException("Could not connect to: " + this.getAddress(), exception);
        } else if (message.equalsIgnoreCase("Auth fail") || message.startsWith("SSH_MSG_DISCONNECT")) {
            // Authentication issue
            throw new ConfigManagerPermanentFailureException("Could not authenticate as: " + this.getUsername()
                    + " on " + this.getAddress());
        } else {
            // Not sure, wrap the exception message and let the caller decide what to do... good luck
            throw new ConfigManagerPermanentFailureException("Unrecognized JSch Exception encountered", exception);
        }
    }

    /**
     * Attempt to setup a JSch Session but we do not connect to the session here
     * 
     * @return true or false if we successfully created the session
     * @throws ConfigManagerException - Temporary or Permanent depending on conditions
     */
    private Session establishSession() throws ConfigManagerException {
        Session session = null;

        try {
            session = this.jsch.getSession(this.getUsername(), this.getAddress());

            // So we aren't asked to verify host keys in our setup environment this is an acceptable security
            // relaxation.
            session.setConfig("StrictHostKeyChecking", "no");

            // Try to notice lost connections
            session.setServerAliveInterval(10000);

            // If we are doing password based authentication
            if (this.getAuthenticationMethod() == BaseVMRemoteConfig.AuthenticationMethod.PASSWORD) {
                session.setUserInfo(this.getUserInfo());
            }
        } catch (JSchException exception) {
            this.handleJSchException(exception);
        }

        return session;
    }

    /**
     * Set the remote address, JSch will accept ipv4
     * 
     * @param address remote address
     */
    private void setAddress(String address) {
        this.address = address;
    }

    /**
     * Used internally to set the AuthenticationMethod
     * 
     * @param method The AuthenticationMethod to set
     * @see AuthenticationMethod
     */
    private void setAuthenticationMethod(AuthenticationMethod method) {
        this.authenticationMethod = method;
    }

    private void setKeyComment(String keyComment) {
        this.keyComment = keyComment;
    }

    /**
     * Used for JSch password authentication
     * 
     * @param userInfo RemoteUserInfo object
     */
    private void setUserInfo(BaseVMRemoteConfig.RemoteUserInfo userInfo) {
        this.userInfo = userInfo;
    }

    /**
     * Set the username to use on the remote
     * 
     * @param username
     */
    private void setUsername(String username) {
        this.username = username;
    }

    /**
     * Attempt to run the given command on the remote host.
     * 
     * @param command The command to run
     * @return CommandOutput with as much detail as we can provide in that
     * @throws ConfigManagerException If there was an issue establishing the channel or session
     */
    public CommandOutput sendCommand(final String command) throws ConfigManagerException {
        return this.sendCommand(command, null);
    }

    /**
     * Attempts to run the primary command and capture its output followed by the secondary command whose output is
     * ignored. Both commands will have their session established at the same time, so this is particularly useful if
     * the first command would alter the authentication details. The intended use of this method is for the
     * primaryCommand to effectively remove this remote configuration access and the secondaryCommand to power down the
     * machine gracefully.
     * 
     * @param primaryCommand - The first command to run and whose output will be returned
     * @param secondaryCommand - The second command to run whose output will be ignored
     * @return CommandOutput with as much detail as we can provide for the primaryCommand
     * @throws ConfigManagerException If there was an issue establishing the channel or session
     */
    public CommandOutput sendCommand(String primaryCommand, String secondaryCommand) throws ConfigManagerException {
        CommandOutput result = null;

        Session session = this.establishSession();

        ChannelExec channel = null;

        try {
            // Connect to our session(s)
            session.connect();

            // Try and open a ChannelExec channel for our primary command
            channel = (ChannelExec) session.openChannel("exec");
            // Capture the output of the primary command
            result = this.execute(primaryCommand, channel);
            // Disconnect the channel
            channel.disconnect();

            if (secondaryCommand != null) {
                // Try and open a ChannelExec channel for the secondary command
                channel = (ChannelExec) session.openChannel("exec");
                // Execute the secondary command
                this.execute(secondaryCommand, channel);
                // Disconnect from the channel
                channel.disconnect();
            }
        } catch (JSchException exception) {
            this.handleJSchException(exception);
        } catch (IOException exception) {
            throw new ConfigManagerTemporaryFailureException(exception);
        } finally {
            // clean up
            if (channel != null && channel.isConnected()) {
                // Close the channel
                channel.disconnect();
            }
            // Close the session(s)

            session.disconnect();

        }

        return result;
    }

    private CommandOutput execute(String command, ChannelExec channel) throws IOException, JSchException {

        // Normal and Error output storage
        StringBuilder output = new StringBuilder(), errorOutput = new StringBuilder();

        // Temp storage to capture stream data
        int tempSize = 1024;
        byte[] temp = new byte[tempSize];

        // Set the command we want to run
        channel.setCommand(command);

        InputStream commandOutputStream = channel.getInputStream(), errorOutputStream = channel.getErrStream();

        // Connect to the channel
        channel.connect();

        // The following is referenced and adapted from
        // http://www.jcraft.com/jsch/examples/Exec.java.html
        while (!channel.isClosed() || commandOutputStream.available() > 0 || errorOutputStream.available() > 0) {

            // Read the standard output if any
            while (commandOutputStream.available() > 0) {
                int i = commandOutputStream.read(temp, 0, tempSize);
                if (i < 0) {
                    break;
                }
                output.append(new String(temp, 0, i));
            }

            // Read the error output if any
            while (errorOutputStream.available() > 0) {
                int i = errorOutputStream.read(temp, 0, tempSize);
                if (i < 0) {
                    break;
                }
                errorOutput.append(new String(temp, 0, i));
            }

            // Attempt to wait a little for the command to finish
            this.attemptWait(1000);
        }

        return new CommandOutput(channel.getExitStatus(), output.toString(), errorOutput.toString(), command);
    }

    /**
     * Set the key as the Default AdminVM key
     * 
     * @return true or false if we could set the key
     */
    private boolean setKey() {
        return this.setKey("AdminVM_id_rsa", null, "root@puppet");
    }

    /**
     * Attempt to setup the session to use the private key given for key authentication.
     * 
     * @param privateKeyFile path to the private key file
     * @param password empty string or password to decrypt private key
     * @param keyComment associated with the public key which will be used if destroyRemoteAccess() is called. @see
     *            #destroyRemoteAccess()
     * @return true or false if we successfully set the key
     */
    private boolean setKey(String privateKeyFile, String password, String keyComment) {
        boolean setKeySuccessfully = false;
        try {
            if (password == null) {
                this.jsch.addIdentity(privateKeyFile);
            } else {
                this.jsch.addIdentity(privateKeyFile, password);
            }
            this.setKeyComment(keyComment);
            setKeySuccessfully = true;
        } catch (JSchException e) {
            // Redundant but explicitly set false because we were unable to set the key
            setKeySuccessfully = false;
        }

        return setKeySuccessfully;
    }

    /**
     * Attempt to run the given bash script on the remote host and capture the exit status and outputs
     * 
     * @param scriptPath the bash script you wish to run
     * @return CommandOutput filled in with the information from running the
     *         script
     * @throws ConfigManagerException When there is any kind of error trying to run the script remotely.
     */
    public CommandOutput runScript(String scriptPath) throws ConfigManagerException {

        // The eventual returned data
        CommandOutput commandOutput = null;
        Session session = null;

        // Get ready to open the file
        File script = new File(scriptPath);
        FileInputStream scriptStream = null;

        // Temporarily use -1 but will hopefully be filled with the exit status of the script
        int exitStatus = -1;

        // Storage for our normal output and error outputs
        StringBuilder output = new StringBuilder(), errorOutput = new StringBuilder();

        try {
            // Try to connect to remote via SSH
            session = this.establishSession();
            session.connect();

            // Try to open a ChannelExec channel
            ChannelExec channel = (ChannelExec) session.openChannel("exec");

            // Get ready to pass the script to the channel's input stream
            scriptStream = new FileInputStream(script);

            // Configure the channel to have bash read in our file
            channel.setCommand("bash -s");
            channel.setInputStream(scriptStream);
            channel.setErrStream(null);

            // The various streams for capturing outputs
            InputStream commandOutputStream = channel.getInputStream(), errorOutputStream = channel.getErrStream();

            // Connect to the channel and get things going
            channel.connect();

            // Temp storage to capture stream data to strings
            int tempSize = 1024;
            byte[] temp = new byte[tempSize];

            while (true) {
                // Read the standard output if any
                while (commandOutputStream.available() > 0) {
                    int i = commandOutputStream.read(temp, 0, tempSize);
                    if (i < 0) {
                        break; // Nothing to do
                    }
                    output.append(new String(temp, 0, i));
                }

                // Read the error output if any
                while (errorOutputStream.available() > 0) {
                    int i = errorOutputStream.read(temp, 0, tempSize);
                    if (i < 0) {
                        break; // Nothing to do
                    }
                    errorOutput.append(new String(temp, 0, i));
                }

                // If the command has finished leave the while(true)
                if (channel.isClosed()) {
                    // record the exit status before leaving
                    exitStatus = channel.getExitStatus();
                    break;
                }

                // Attempt to wait a little while the command is executing
                this.attemptWait(1000);
            }

            scriptStream.close();
            channel.disconnect();

        } catch (JSchException exception) {
            // Figure out what non recoverable JSchException occurred
            this.handleJSchException(exception);
        } catch (FileNotFoundException exception) {
            // Script not found, not recoverable
            throw new ConfigManagerPermanentFailureException("Script not found at " + scriptPath, exception);
        } catch (IOException exception) {
            // Problem with our streams, not recoverable
            throw new ConfigManagerPermanentFailureException(exception);
        } finally {
            // clean up
            if (session != null) {
                session.disconnect();
            }
        }

        // We haven't thrown any errors at this point store the results of the command execution
        commandOutput = new CommandOutput(exitStatus, output.toString(), errorOutput.toString(), "Equivalent to: ssh "
                + this.getUsername() + "@" + this.getAddress() + " \"bash -s <\" " + scriptPath);

        return commandOutput;
    }
}
