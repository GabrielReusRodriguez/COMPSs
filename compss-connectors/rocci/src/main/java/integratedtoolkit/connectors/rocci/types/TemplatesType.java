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

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for TemplatesType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="TemplatesType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="Instance" type="{}InstanceType" maxOccurs="unbounded"/>
 *         &lt;element name="EstimatedCreationTime" type="{http://www.w3.org/2001/XMLSchema}int"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "TemplatesType", propOrder = {
    "instance",
    "estimatedCreationTime"
})
public class TemplatesType {

    @XmlElement(name = "Instance", required = true)
    protected List<InstanceType> instance;
    @XmlElement(name = "EstimatedCreationTime")
    protected int estimatedCreationTime;

    /**
     * Gets the value of the instance property.
     * 
     * <p>
     * This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the instance property.
     * 
     * <p>
     * For example, to add a new item, do as follows:
     * <pre>
     *    getInstance().add(newItem);
     * </pre>
     * 
     * 
     * <p>
     * Objects of the following type(s) are allowed in the list
     * {@link InstanceType }
     * 
     * 
     */
    public List<InstanceType> getInstance() {
        if (instance == null) {
            instance = new ArrayList<InstanceType>();
        }
        return this.instance;
    }

    /**
     * Gets the value of the estimatedCreationTime property.
     * 
     */
    public int getEstimatedCreationTime() {
        return estimatedCreationTime;
    }

    /**
     * Sets the value of the estimatedCreationTime property.
     * 
     */
    public void setEstimatedCreationTime(int value) {
        this.estimatedCreationTime = value;
    }

}
