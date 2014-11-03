/*
 *  Copyright 2002-2014 Barcelona Supercomputing Center (www.bsc.es)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */





package integratedtoolkit.connectors.rocci.types;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for InstanceType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="InstanceType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="Type" type="{http://www.w3.org/2001/XMLSchema}string"/>
 *         &lt;element name="CPU" type="{http://www.w3.org/2001/XMLSchema}int"/>
 *         &lt;element name="Memory" type="{http://www.w3.org/2001/XMLSchema}decimal"/>
 *         &lt;element name="Disk" type="{http://www.w3.org/2001/XMLSchema}int"/>
 *         &lt;element name="Price" type="{http://www.w3.org/2001/XMLSchema}decimal"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "InstanceType", propOrder = {
    "type",
    "cpu",
    "memory",
    "disk",
    "price"
})
public class InstanceType {

    @XmlElement(name = "Type", required = true)
    protected String type;
    @XmlElement(name = "CPU")
    protected int cpu;
    @XmlElement(name = "Memory", required = true)
    protected Float memory;
    @XmlElement(name = "Disk")
    protected int disk;
    @XmlElement(name = "Price", required = true)
    protected Float price;

    /**
     * Gets the value of the type property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getType() {
        return type;
    }

    /**
     * Sets the value of the type property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setType(String value) {
        this.type = value;
    }

    /**
     * Gets the value of the cpu property.
     * 
     */
    public int getCPU() {
        return cpu;
    }

    /**
     * Sets the value of the cpu property.
     * 
     */
    public void setCPU(int value) {
        this.cpu = value;
    }

    /**
     * Gets the value of the memory property.
     * 
     * @return
     *     possible object is
     *     {@link Float }
     *     
     */
    public Float getMemory() {
        return memory;
    }

    /**
     * Sets the value of the memory property.
     * 
     * @param value
     *     allowed object is
     *     {@link Float }
     *     
     */
    public void setMemory(Float value) {
        this.memory = value;
    }

    /**
     * Gets the value of the disk property.
     * 
     */
    public int getDisk() {
        return disk;
    }

    /**
     * Sets the value of the disk property.
     * 
     */
    public void setDisk(int value) {
        this.disk = value;
    }

    /**
     * Gets the value of the price property.
     * 
     * @return
     *     possible object is
     *     {@link Float }
     *     
     */
    public Float getPrice() {
        return price;
    }

    /**
     * Sets the value of the price property.
     * 
     * @param value
     *     allowed object is
     *     {@link Float }
     *     
     */
    public void setPrice(Float value) {
        this.price = value;
    }

}
