package edu.uri.dfcsc.occp;

/**
 * A class to represent a DNS entry
 */
public class OccpDNSEntry {
    // <entry name="www.kittenmittens.com" ttl="1440" class="IN" rrtype="TXT" value="web server"/>
    /**
     * Left-hand side of entry
     */
    public String entryName;
    /**
     * Time-To-Live for this entry
     */
    public int ttl;
    /**
     * Class of this entry (usually "IN")
     */
    public String entryClass;
    /**
     * Type of entry (A, PTR, CNAME, NS, MX, etc)
     */
    public String entryType;
    /**
     * Value of this entry
     */
    public String entryValue;

    OccpDNSEntry(String _entryName, int _ttl, String _entryClass, String _entryType, String _entryValue) {
        this.entryName = _entryName;
        this.ttl = _ttl;
        this.entryClass = _entryClass;
        this.entryType = _entryType;
        this.entryValue = _entryValue;
    }
}
