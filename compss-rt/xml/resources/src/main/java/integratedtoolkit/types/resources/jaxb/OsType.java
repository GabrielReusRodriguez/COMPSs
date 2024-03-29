//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.4 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2014.11.03 at 03:58:58 PM CET 
//


package integratedtoolkit.types.resources.jaxb;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for osType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="osType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="OSType">
 *           &lt;simpleType>
 *             &lt;restriction base="{http://www.w3.org/2001/XMLSchema}string">
 *               &lt;enumeration value="Linux"/>
 *               &lt;enumeration value="WindowsXP"/>
 *               &lt;enumeration value="WindowsVista"/>
 *               &lt;enumeration value="Windows7"/>
 *               &lt;enumeration value="Windows"/>
 *             &lt;/restriction>
 *           &lt;/simpleType>
 *         &lt;/element>
 *         &lt;element name="PhysicalMemory" type="{http://www.w3.org/2001/XMLSchema}float" minOccurs="0"/>
 *         &lt;element name="VirtualMemory" type="{http://www.w3.org/2001/XMLSchema}float" minOccurs="0"/>
 *         &lt;element name="MaxProcessesPerUser" type="{http://www.w3.org/2001/XMLSchema}int" minOccurs="0"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "osType", propOrder = {
    "osType",
    "physicalMemory",
    "virtualMemory",
    "maxProcessesPerUser"
})
public class OsType {

    @XmlElement(name = "OSType", required = true)
    protected String osType;
    @XmlElement(name = "PhysicalMemory")
    protected Float physicalMemory;
    @XmlElement(name = "VirtualMemory")
    protected Float virtualMemory;
    @XmlElement(name = "MaxProcessesPerUser")
    protected Integer maxProcessesPerUser;

    /**
     * Gets the value of the osType property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getOSType() {
        return osType;
    }

    /**
     * Sets the value of the osType property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setOSType(String value) {
        this.osType = value;
    }

    /**
     * Gets the value of the physicalMemory property.
     * 
     * @return
     *     possible object is
     *     {@link Float }
     *     
     */
    public Float getPhysicalMemory() {
        return physicalMemory;
    }

    /**
     * Sets the value of the physicalMemory property.
     * 
     * @param value
     *     allowed object is
     *     {@link Float }
     *     
     */
    public void setPhysicalMemory(Float value) {
        this.physicalMemory = value;
    }

    /**
     * Gets the value of the virtualMemory property.
     * 
     * @return
     *     possible object is
     *     {@link Float }
     *     
     */
    public Float getVirtualMemory() {
        return virtualMemory;
    }

    /**
     * Sets the value of the virtualMemory property.
     * 
     * @param value
     *     allowed object is
     *     {@link Float }
     *     
     */
    public void setVirtualMemory(Float value) {
        this.virtualMemory = value;
    }

    /**
     * Gets the value of the maxProcessesPerUser property.
     * 
     * @return
     *     possible object is
     *     {@link Integer }
     *     
     */
    public Integer getMaxProcessesPerUser() {
        return maxProcessesPerUser;
    }

    /**
     * Sets the value of the maxProcessesPerUser property.
     * 
     * @param value
     *     allowed object is
     *     {@link Integer }
     *     
     */
    public void setMaxProcessesPerUser(Integer value) {
        this.maxProcessesPerUser = value;
    }

}
