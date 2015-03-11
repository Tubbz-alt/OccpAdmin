package edu.uri.dfcsc.occp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.*;
import org.xml.sax.SAXException;

/**
 * Creates a Hypervisor given command line arguments or vmname
 * Hypervisor configurations can be stored in hypervisors.xml
 * 
 * @author Kevin Bryan (bryank@cs.uri.edu)
 */
public class OccpHVFactory {
    private static Logger logger = Logger.getLogger(OccpHVFactory.class.getName());
    private final static String hvFileName = OccpAdmin.occpHiddenDirPath.resolve("hypervisors.xml").toString();

    /**
     * @param hvname Name of the Hypervisor for use with {@code --hvname} or {@code --hvmap}
     * @param attributes Connection parameters
     * @return True if the values were successfully stored, false otherwise
     */
    public static boolean cacheHypervsior(String hvname, Map<String, String> attributes) {
        try {
            DocumentBuilderFactory dBF = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = dBF.newDocumentBuilder();
            Document doc;
            // Read the current file and append
            try {
                doc = docBuilder.parse(hvFileName);
                doc.getDocumentElement().normalize();
                NodeList hvs = doc.getElementsByTagName("hypervisor");
                for (int index = 0; index < hvs.getLength(); ++index) {
                    Element hv = (Element) hvs.item(index);
                    if (hv.getAttribute("name").equals(hvname)) {
                        logger.severe("A hypervisor with that name already exists");
                        return false;
                    }
                }
            } catch (SAXException e) {
                // Leave it to the user to figure out what went wrong
                logger.log(Level.SEVERE, "Invalid configuration file " + hvFileName, e);
                return false;
            } catch (FileNotFoundException e) {
                doc = docBuilder.newDocument();
                Element root = doc.createElement("hypervisors");
                doc.appendChild(root);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error reading hypervisors configuration file " + hvFileName, e);
                return false;
            }
            Text tab = doc.createTextNode("\t");
            doc.getFirstChild().appendChild(tab);
            Element newHv = doc.createElement("hypervisor");
            newHv.setAttribute("name", hvname);
            for (Entry<String, String> e : attributes.entrySet()) {
                newHv.setAttribute(e.getKey(), e.getValue());
            }
            doc.getFirstChild().appendChild(newHv);
            Text newLn = doc.createTextNode("\n");
            doc.getFirstChild().appendChild(newLn);
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(new File(hvFileName));
            transformer.transform(source, result);
        } catch (TransformerException e) {
            logger.log(Level.SEVERE, "Could not write configuration file " + hvFileName, e);
            return false;
        } catch (ParserConfigurationException e) {
            logger.log(Level.SEVERE, "Could not read/write configuration file " + hvFileName, e);
            return false;
        }
        return true;
    }

    /**
     * Return all the details of all the hypervisors as a two level map
     *
     * @return Hypervisor details
     */
    public static Map<String, Map<String, String>> getAllHypervisors() {
        Document doc;
        Map<String, Map<String, String>> hvDetails = new TreeMap<>();
        try {
            DocumentBuilderFactory dBF = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = dBF.newDocumentBuilder();
            // Read the current file and append
            doc = docBuilder.parse(hvFileName);
            doc.getDocumentElement().normalize();
            NodeList hvs = doc.getElementsByTagName("hypervisor");
            for (int index = 0; index < hvs.getLength(); ++index) {
                Element hv = (Element) hvs.item(index);
                String hvname = hv.getAttribute("name");
                hvDetails.put(hvname, new TreeMap<>());
                NamedNodeMap nnm = hv.getAttributes();
                for (int attr = 0; attr < nnm.getLength(); ++attr) {
                    Attr a = (Attr) nnm.item(attr);
                    hvDetails.get(hvname).put(a.getName(), a.getValue());
                }
            }
        } catch (SAXException e) {
            // Leave it to the user to figure out what went wrong
            logger.log(Level.SEVERE, "Could not read stored hypervisor information (invalid)" + hvFileName, e);
            return null;
        } catch (FileNotFoundException e) {
            logger.severe("No stored hypervisor information found: " + hvFileName);
            return null;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not read stored hypervisor information " + hvFileName, e);
            return null;
        } catch (ParserConfigurationException e) {
            logger.log(Level.SEVERE, "Could not read stored hypervisor information (Parser)", e);
            return null;
        }
        return hvDetails;
    }

    private static boolean getCachedHypervsior(String hvname, Map<String, String> attributes) {
        Document doc;
        try {
            DocumentBuilderFactory dBF = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = dBF.newDocumentBuilder();

            // Read the current file and append
            doc = docBuilder.parse(hvFileName);
            doc.getDocumentElement().normalize();
            NodeList hvs = doc.getElementsByTagName("hypervisor");
            for (int index = 0; index < hvs.getLength(); ++index) {
                Element hv = (Element) hvs.item(index);
                if (hv.getAttribute("name").equals(hvname)) {
                    NamedNodeMap nnm = hv.getAttributes();
                    for (int attr = 0; attr < nnm.getLength(); ++attr) {
                        Attr a = (Attr) nnm.item(attr);
                        attributes.put(a.getName(), a.getValue());
                    }
                    return true;
                }
            }
        } catch (SAXException e) {
            // Leave it to the user to figure out what went wrong
            logger.log(Level.SEVERE, "Could not read stored hypervisor information (invalid)" + hvFileName, e);
            return false;
        } catch (FileNotFoundException e) {
            logger.severe("No stored hypervisor information found: " + hvFileName);
            return false;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not read stored hypervisor information " + hvFileName, e);
            return false;
        } catch (ParserConfigurationException e) {
            logger.log(Level.SEVERE, "Could not read stored hypervisor information (Parser)", e);
            return false;
        }
        return false;
    }

    /**
     * @param name Temporary name
     * @param hypervisor Reference name for the hypervisor
     * @param args Command-line arguments to configure hypervisor
     * @return Handle to the hypervisor
     */
    public static OccpHV getOccpHVFromArgs(String name, String hypervisor, String[] args) {
        return getOccpHVByType(name, hypervisor, args, null);
    }

    /**
     * @param hvName Name of the hypervisor to load settings
     * @param args Command-line arguments override/augment file settings
     * @return Handle to the hypervisor
     */
    public static OccpHV getOccpHVFromFile(String hvName, String[] args) {
        HashMap<String, String> profile = new HashMap<String, String>();
        if (!OccpHVFactory.getCachedHypervsior(hvName, profile)) {
            logger.severe("Missing entry for " + hvName + " in " + OccpHVFactory.hvFileName);
            return null;
        }
        String hypervisor = profile.get("hypervisor");
        if (hypervisor == null) {
            logger.severe("Missing entry for " + hvName + " in " + OccpHVFactory.hvFileName);
            return null;
        }
        OccpHV hv = getOccpHVByType(hvName, hypervisor, args, profile);
        if (hv == null) {
            return null;
        }
        if (profile.containsKey("local") && profile.get("local").equals("true")) {
            hv.setLocal(true);
        }
        return hv;
    }

    private static OccpHV getOccpHVByType(String name, String type, String[] args, Map<String, String> profile) {
        OccpHV hv;
        if (type.equalsIgnoreCase("vcenter") || type.equalsIgnoreCase("esxi") || type.equalsIgnoreCase("workstation")
                || type.equalsIgnoreCase("vmware")) {
            hv = new OccpEsxiHV(name, profile);
        } else if (type.equalsIgnoreCase("vbox")) {
            hv = new OccpVBoxHV(name, profile);
        } else {
            logger.severe("Hypervisor type not supported");
            return null;
        }
        if (!hv.parseArgs(args)) {
            return null;
        }
        return hv;
    }

    /**
     * Remove the named entry from the cache file
     * 
     * @param hvName - Name to remove from the cache file
     * @return Success/failure
     */
    public static boolean removeHypervisor(String hvName) {
        boolean removed = false;
        try {
            DocumentBuilderFactory dBF = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = dBF.newDocumentBuilder();
            Document doc;
            // Read the current file and append
            try {
                doc = docBuilder.parse(hvFileName);
                doc.getDocumentElement().normalize();
                NodeList hvs = doc.getElementsByTagName("hypervisor");
                for (int index = 0; index < hvs.getLength(); ++index) {
                    Element hv = (Element) hvs.item(index);
                    if (hv.getAttribute("name").equals(hvName)) {
                        hv.getParentNode().removeChild(hv);
                        removed = true;
                    }
                }
            } catch (SAXException e) {
                // Leave it to the user to figure out what went wrong
                logger.log(Level.SEVERE, "Invalid configuration file " + hvFileName, e);
                return false;
            } catch (FileNotFoundException e) {
                doc = docBuilder.newDocument();
                Element root = doc.createElement("hypervisors");
                Text newLn = doc.createTextNode("\n");
                root.appendChild(newLn);
                doc.appendChild(root);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error reading hypervisors configuration file " + hvFileName, e);
                return false;
            }
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(new File(hvFileName));
            transformer.transform(source, result);
        } catch (TransformerException e) {
            logger.log(Level.SEVERE, "Could not write configuration file " + hvFileName, e);
            return false;
        } catch (ParserConfigurationException e) {
            logger.log(Level.SEVERE, "Could not read/write configuration file " + hvFileName, e);
            return false;
        }
        if (!removed) {
            logger.severe(hvName + " not found in " + hvFileName);
        }
        return removed;
    }
}
