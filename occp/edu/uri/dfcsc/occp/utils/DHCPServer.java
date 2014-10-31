package edu.uri.dfcsc.occp.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.uri.dfcsc.occp.OccpAdmin;

/**
 * Wrapper class for running a dnsmasq DHCP server The call() method will wait
 * for the child process to stop
 * 
 * @author root
 */
public class DHCPServer {
    private Process dhcp = null;
    int retVal = -255;
    boolean isReady = false;
    private static Logger logger = Logger.getLogger(DHCPServer.class.getName());
    boolean haveTried = false;
    private Thread watchThread;

    /**
     * Test if the server is started and ready
     * 
     * @return True if the server is running
     */
    public boolean ensureRunning() {
        try {
            if (dhcp == null || !isReady) {
                setup();
            }
            retVal = dhcp.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /**
     * Test if the server is started and ready
     * 
     * @return True if the server is running
     */
    public boolean isRunning() {
        try {
            if (dhcp == null || !isReady) {
                return false;
            }
            retVal = dhcp.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /**
     * Start the service
     * 
     * @return True if the service started properly
     */
    public synchronized Boolean setup() {
        String[] ifcfg = { "sudo", "ifconfig", "br0", "12.14.16.1", "netmask", "255.255.0.0", "up" };
        Runtime rt = Runtime.getRuntime();
        try {
            rt.exec(ifcfg);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error bringing up setup interface", e);
        }
        String[] cmd = { "sudo", "dnsmasq", "--no-daemon",
                "--conf-file=" + OccpAdmin.occpHiddenDirPath.resolve("dnsmasq.conf").toString() };
        ProcessBuilder pb = new ProcessBuilder(Arrays.asList(cmd));
        if (haveTried) {
            return isReady;
        }
        BufferedReader reader = null;
        try {
            pb.redirectInput(Redirect.INHERIT).redirectOutput(Redirect.INHERIT).redirectError(Redirect.PIPE);
            // Ensure it isn't running already; mostly for debug
            // purposes
            this.stop();
            haveTried = true;
            dhcp = pb.start();
            InputStream stderr = dhcp.getErrorStream();
            reader = new BufferedReader(new InputStreamReader(stderr));
            while (!isReady) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                logger.fine(line);
                if (line.startsWith("dnsmasq-dhcp: read ")) {
                    isReady = true;
                    watchThread = new Thread(new WatchDnsmasq());
                    watchThread.start();
                    break;
                } else if (line.startsWith("dnsmasq: failed to create listening socket")) {
                    isReady = false;
                    break;
                }
            }
        } catch (IOException e) {
            isReady = false;
            logger.log(Level.SEVERE, "Failed to start DNSMASQ", e);
            if (dhcp != null) {
                dhcp.destroy();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ioe) {
                    logger.warning("Failed to close handle to dnsmasq");
                }
            }
            return false;
        }
        return isReady;
    }

    /**
     * Force a shutdown of the service
     */
    public void stop() {
        // Stop any existing dnsmasq process
        Runtime rt = Runtime.getRuntime();
        String[] cmd = { "sudo", "pkill", "-TERM", "dnsmasq" };

        // This ensures that it is shutdown from a previous run
        logger.finest("Stopping dnsmasq");
        try {
            retVal = rt.exec(cmd).waitFor();
        } catch (InterruptedException e) {

        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to stop DNSMASQ", e);
        }

        if (dhcp != null && isReady) {
            try {
                dhcp.getErrorStream().close();
            } catch (IOException e) {
                // Ignore errors
            }
            // This should already be done by above
            dhcp.destroy();
            synchronized (this) {
                if (watchThread != null) {
                    try {
                        watchThread.join(5000);
                    } catch (InterruptedException e) {
                        // Ignore errors
                    }
                }
            }
        }

    }

    /* Monitor the dnsmasq sub-process */
    private class WatchDnsmasq implements Runnable {
        @Override
        public void run() {
            Thread.currentThread().setName("DNSMasq Watcher");
            BufferedReader reader = null;
            try {
                InputStream stdin = dhcp.getErrorStream();
                reader = new BufferedReader(new InputStreamReader(stdin));
                String line;
                do {
                    line = reader.readLine();
                    if (line != null) {
                        logger.fine(line);
                    }
                } while (line != null);
                retVal = dhcp.waitFor();
            } catch (InterruptedException | IOException e) {
                isReady = false;
                // Old Java API doesn't seem to give better mechanism for determining cause
                if (!e.getMessage().contains("Stream closed")) {
                    logger.log(Level.WARNING, "Failure running dnsmasq", e);
                }
            } finally {
                isReady = false;
                logger.finest("WatchDnsmasq shutting down");
                if (dhcp != null) {
                    dhcp.destroy();
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        logger.log(Level.SEVERE, "Failed to close dnsmasq file handle", e);
                    }
                }
            }
        }
    }
}
