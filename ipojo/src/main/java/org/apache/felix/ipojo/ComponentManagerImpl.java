/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.ipojo;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.logging.Level;

import org.apache.felix.ipojo.metadata.Element;
import org.osgi.framework.BundleContext;

/**
 * The component manager implementation class manages one instance of a component type.
 * It manages component lifecycle, component instance creation and handlers.
 * @author <a href="mailto:felix-dev@incubator.apache.org">Felix Project Team</a>
 */
public class ComponentManagerImpl implements ComponentManager {

    /**
     * Parent factory (ComponentManagerFactory).
     */
    private ComponentManagerFactory m_factory;

    /**
     * Attached metadata of the managed component.
     */
    private ComponentMetadata m_metadata;

    /**
     * The context of the component.
     */
    private BundleContext m_context;

    /**
     * Handler list.
     */
    private Handler[] m_handlers = new Handler[0];

    /**
     * Map [field, handler list] storing handlers interested by the field.
     */
    private HashMap m_fieldRegistration = new HashMap();

    /**
     * Component state (STOPPED at the beginning).
     */
    private int m_state = INVALID;

    // Fields use for the manipulation, the loading of the class and for the instance creation

    /**
     * Manipulatd clazz.
     */
    private Class m_clazz;

    /**
     * Instances of the components.
     */
    private Object[] m_instances = new Object[0];

    /**
     * Component type information.
     */
    private ComponentInfo m_componentInfo;

    // Constructor
    /**
     * Construct a new Component Manager.
     * @param factory : the factory managing the component manager
     */
    public ComponentManagerImpl(ComponentManagerFactory factory) {
        m_factory = factory;
        m_context = factory.getBundleContext();
        Activator.getLogger().log(Level.INFO, "[Bundle " + m_context.getBundle().getBundleId() + "] Create a component manager from the factory " + m_factory);
    }

    /**
     * Configure the component manager.
     * Stop the existings handler, clear the handler list, change the metadata, recreate the handlers
     * @param cm
     */
    public void configure(Element cm, Dictionary configuration) {
        Activator.getLogger().log(Level.INFO, "[Bundle " + m_context.getBundle().getBundleId() + "] Configure the component manager " + cm.getAttribute("className"));

        // Stop all previous registred handler
        if (m_handlers.length != 0) { stop(); }

        // Clear the handler list
        m_handlers = new Handler[0];

        // Change the metadata
        m_metadata = new ComponentMetadata(cm);

        // COmponentInfo initialization
        m_componentInfo = new ComponentInfo();
        m_componentInfo.setClassName(m_metadata.getClassName());

        // Create the standard handlers and add these handlers to the list
        for (int i = 0; i < IPojoConfiguration.INTERNAL_HANDLERS.length; i++) {
            // Create a new instance
            try {
                Handler h = (Handler) IPojoConfiguration.INTERNAL_HANDLERS[i].newInstance();
                h.configure(this, cm, configuration);
            } catch (InstantiationException e) {
                Activator.getLogger().log(Level.SEVERE, "[" + m_metadata.getClassName() + "] Cannot instantiate the handler " + IPojoConfiguration.INTERNAL_HANDLERS[i] + " : " + e.getMessage());
            } catch (IllegalAccessException e) {
                Activator.getLogger().log(Level.SEVERE, "[" + m_metadata.getClassName() + "] Cannot instantiate the handler " + IPojoConfiguration.INTERNAL_HANDLERS[i] + " : " + e.getMessage());
            }
        }

        // Look for namespaces
        for (int i = 0; i < cm.getNamespaces().length; i++) {
            if (!cm.getNamespaces()[i].equals("")) {
                Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] Look for class for the namespace : " + cm.getNamespaces()[i]);
                // It is not an internal handler, try to load it
                try {
                    Class c = m_context.getBundle().loadClass(cm.getNamespaces()[i]);
                    Handler h = (Handler) c.newInstance();
                    h.configure(this, cm, configuration);
                } catch (ClassNotFoundException e) {
                    Activator.getLogger().log(Level.SEVERE, "[" + m_metadata.getClassName() + "] Cannot instantiate the handler " + cm.getNamespaces()[i] + " : " + e.getMessage());
                } catch (InstantiationException e) {
                    Activator.getLogger().log(Level.SEVERE, "[" + m_metadata.getClassName() + "] Cannot instantiate the handler " + cm.getNamespaces()[i] + " : " + e.getMessage());
                } catch (IllegalAccessException e) {
                    Activator.getLogger().log(Level.SEVERE, "[" + m_metadata.getClassName() + "] Cannot instantiate the handler " + cm.getNamespaces()[i] + " : " + e.getMessage());
                }

            }
        }

    }

    /**
     * @return the component type information.
     */
    public ComponentInfo getComponentInfo() { return m_componentInfo; }

    /**
     * @return the component metadata.
     */
    public ComponentMetadata getComponentMetatada() { return m_metadata; }

    /**
     * @return the list of the registred handlers.
     */
    public Handler[] getRegistredHandlers() { return m_handlers; }

    /**
     * Return a specified handler.
     * @param name : class name of the handler to find
     * @return : the handler, or null if not found
     */
    public Handler getHandler(String name) {
        for (int i = 0; i < m_handlers.length; i++) {
            if (m_handlers[i].getClass().getName().equalsIgnoreCase(name)) { return m_handlers[i]; }
        }
        return null;
    }

    // ===================== Lifecycle management =====================

    /**
     * Start the component manager.
     */
    public void start() {
        // Start all the handlers
        Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] Start the component manager with " + m_handlers.length + " handlers");

        // The new state of the component is UNRESOLVED
        m_state = INVALID;

        for (int i = 0; i < m_handlers.length; i++) {
            m_handlers[i].start();
        }

        // Defines the state of the component :
        checkComponentState();
    }

    /**
     * Stop the component manager.
     */
    public void stop() {
        setState(INVALID);
        // Stop all the handlers
        for (int i = m_handlers.length - 1; i > -1; i--) {
            m_handlers[i].stop();
        }
        m_instances = new Object[0];
    }

    /**
     * Set the state of the component.
     * if the state changed call the stateChanged(int) method on the handlers
     */
    public void setState(int state) {
        if (m_state != state) {

            // Log the state change
            if (state == INVALID) { Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] Component " + m_metadata.getClassName() + " State -> UNRESOLVED"); }
            if (state == VALID) { Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] Component " + m_metadata.getClassName() + " State -> VALID"); }

            // The state changed call the handler stateChange method
            m_state = state;
            for (int i = m_handlers.length - 1; i > -1; i--) {
                m_handlers[i].stateChanged(state);
            }
        }
    }

    /**
     * @return the actual state of the component.
     */
    public int getState() {
        return m_state;
    }

    // ===================== end Lifecycle management =====================

    // ================== Class & Instance management ===================

    /**
     * @return the factory of the component
     */
    public ComponentManagerFactory getFactory() { return m_factory; }

    /**
     * Load the manipulated class.
     */
    private void load() {
        try {
            m_clazz = m_factory.loadClass(m_metadata.getClassName());
        } catch (ClassNotFoundException  e) {
            Activator.getLogger().log(Level.SEVERE, "[" + m_metadata.getClassName() + "] Class not found during the loading phase : " + e.getMessage());
            return;
        }
    }

    /**
     * @return true if the class is loaded
     */
    private boolean isLoaded() {
        return (m_clazz != null);
    }

    /**
     * Add an instance to the created instance list.
     * @param o : the instance to add
     */
    private void addInstance(Object o) {
        for (int i = 0; (m_instances != null) && (i < m_instances.length); i++) {
            if (m_instances[i] == o) { return; }
        }

        if (m_instances.length > 0) {
            Object[] newInstances = new Object[m_instances.length + 1];
            System.arraycopy(m_instances, 0, newInstances, 0, m_instances.length);
            newInstances[m_instances.length] = o;
            m_instances = newInstances;
        }
        else {
            m_instances = new Object[] {o};
        }
    }

    /**
     * Remove an instance from the created instance list. The instance will be eated by the garbage collector.
     * @param o : the instance to remove
     */
    private void removeInstance(Object o) {
        int idx = -1;
        for (int i = 0; i < m_instances.length; i++) {
            if (m_instances[i] == o) { idx = i; break; }
        }

        if (idx >= 0) {
            if ((m_instances.length - 1) == 0) { m_instances = new Element[0]; }
            else {
                Object[] newInstances = new Object[m_instances.length - 1];
                System.arraycopy(m_instances, 0, newInstances, 0, idx);
                if (idx < newInstances.length) {
                    System.arraycopy(m_instances, idx + 1, newInstances, idx, newInstances.length - idx); }
                m_instances = newInstances;
            }
        }
    }

    /**
     * @return the created instance of the component.
     */
    public Object[] getInstances() { return m_instances; }

    /**
     * Delete the created instance (remove it from the list, to allow the garbage collector to eat the instance).
     * @param o : the instance to delete
     */
    public void deleteInstance(Object o) { removeInstance(o); }

    /**
     * Create an instance of the component.
     * This method need to be called one time only for singleton provided service
     * @return a new instance
     */
    public Object createInstance() {

        if (!isLoaded()) { load(); }
        Object instance = null;
        try {
            Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] createInstance -> Try to find the constructor");

            // Try to find if there is a constructor with a bundle context as parameter :
            try {
                Constructor constructor = m_clazz.getConstructor(new Class[] {ComponentManagerImpl.class, BundleContext.class});
                constructor.setAccessible(true);
                instance = constructor.newInstance(new Object[] {this, m_factory.getBundleContext()});
            }
            catch (NoSuchMethodException e) {
                Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] createInstance -> No constructor with a bundle context");
            }

            // Create an instance if no instance are already created with <init>()BundleContext
            Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] createInstance -> Try to create the object with an empty constructor");
            if (instance == null) {
                Constructor constructor = m_clazz.getConstructor(new Class[] {ComponentManagerImpl.class});
                constructor.setAccessible(true);
                instance = constructor.newInstance(new Object[] {this});
            }

        } catch (InstantiationException e) {
            Activator.getLogger().log(Level.SEVERE, "[" + m_metadata.getClassName() + "] createInstance -> The Component Instance cannot be instancied : " + e.getMessage());
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            Activator.getLogger().log(Level.SEVERE, "[" + m_metadata.getClassName() + "] createInstance -> The Component Instance is not accessible : " + e.getMessage());
            e.printStackTrace();
        } catch (SecurityException e) {
            Activator.getLogger().log(Level.SEVERE, "[" + m_metadata.getClassName() + "] createInstance -> The Component Instance is not accessible (security reason) : " + e.getMessage());
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            Activator.getLogger().log(Level.SEVERE, "[" + m_metadata.getClassName() + "] createInstance -> Cannot invoke the constructor method (illegal target) : " + e.getMessage());
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            Activator.getLogger().log(Level.SEVERE, "[" + m_metadata.getClassName() + "] createInstance -> Cannot invoke the constructor (method not found) : " + e.getMessage());
            e.printStackTrace();
        }

        Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] createInstance -> Return the instance " + instance);

        // Register the new instance
        addInstance(instance);
        // Call createInstance on Handlers :
        for (int i = 0; i < m_handlers.length; i++) { m_handlers[i].createInstance(instance); }
        return instance;
    }

    /**
     * @return the instance of the component to use for singleton component
     */
    public Object getInstance() {
        if (m_instances.length == 0) { createInstance(); }
        return m_instances[0];
    }

    /**
     * @return the manipulated class
     */
    public Class getClazz() {
        if (!isLoaded()) { load(); }
        return m_clazz;
    }

    //  ================== end Class & Instance management ================

    //  ======================== Handlers Management ======================

    /**
     * Register the given handler to the current component manager.
     * @param h : the handler to register
     */
    public void register(Handler h) {
        for (int i = 0; (m_handlers != null) && (i < m_handlers.length); i++) {
            if (m_handlers[i] == h) {
                return;
            }
        }

        if (m_handlers != null) {
            Handler[] newList = new Handler[m_handlers.length + 1];
            System.arraycopy(m_handlers, 0, newList, 0, m_handlers.length);
            newList[m_handlers.length] = h;
            m_handlers = newList;
        }
    }

    /**
     * Register an handler.
     * The handler will be notified of event on each field given in the list.
     * @param h : the handler to register
     * @param fields : the fields list
     */
    public void register(Handler h, String[] fields) {
        register(h);
        for (int i = 0; i < fields.length; i++) {
            if (m_fieldRegistration.get(fields[i]) == null) {
                m_fieldRegistration.put(fields[i], new Handler[] {h});
            }
            else {
                Handler[] list = (Handler[]) m_fieldRegistration.get(fields[i]);
                for (int j = 0; j < list.length; i++) { if (list[i] == h) { return; } }
                Handler[] newList = new Handler[list.length + 1];
                System.arraycopy(list, 0, newList, 0, list.length);
                newList[list.length] = h;
                m_fieldRegistration.put(fields[i], newList);
            }
        }
    }

    /**
     * Unregister an handler for the field list.
     * The handler will not be notified of field access but is allways register on the component manager.
     * @param h : the handler to unregister.
     * @param fields : the fields list
     */
    public void unregister(Handler h, String[] fields) {
        for (int i = 0; i < fields.length; i++) {
            if (m_fieldRegistration.get(fields[i]) == null) { break; }
            else {
                Handler[] list = (Handler[]) m_fieldRegistration.get(fields[i]);
                int idx = -1;
                for (int j = 0; j < list.length; j++) {
                    if (list[j] == h) {
                        idx = j;
                        break;
                    }
                }

                if (idx >= 0) {
                    if ((list.length - 1) == 0) {
                        list = new Handler[0];
                    }
                    else {
                        Handler[] newList = new Handler[list.length - 1];
                        System.arraycopy(list, 0, newList, 0, idx);
                        if (idx < newList.length)             {
                            System.arraycopy(
                                    list, idx + 1, newList, idx, newList.length - idx);
                        }
                        list = newList;
                    }
                    m_fieldRegistration.put(fields[i], list);
                }
            }
        }
    }

    /**
     * Unregister the given handler.
     * @param h : the handler to unregiter
     */
    public void unregister(Handler h) {
        int idx = -1;
        for (int i = 0; i < m_handlers.length; i++) {
            if (m_handlers[i] == h) {
                idx = i;
                break;
            }
        }

        if (idx >= 0) {
            if ((m_handlers.length - 1) == 0) {
                m_handlers = new Handler[0];
            }
            else {
                Handler[] newList = new Handler[m_handlers.length - 1];
                System.arraycopy(m_handlers, 0, newList, 0, idx);
                if (idx < newList.length)             {
                    System.arraycopy(
                            m_handlers, idx + 1, newList, idx, newList.length - idx);
                }
                m_handlers = newList;
            }
        }
    }

    /**
     * This method is called by the manipulated class each time that a GETFIELD instruction is found.
     * The method ask to each handler which value need to be returned.
     * @param fieldName : the field name on which the GETFIELD instruction is called
     * @param initialValue : the value of the field in the code
     * @return the value decided by the last asked handler (throw a warining if two fields decide two different values)
     */
    public Object getterCallback(String fieldName, Object initialValue) {
        Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] Call the getterCallbackMethod on " + fieldName +  " with " + initialValue);
        Object result = null;
        // Get the list of registered handlers
        Handler[] list = (Handler[]) m_fieldRegistration.get(fieldName);
        for (int i = 0; list != null && i < list.length; i++) {
            Object handlerResult = list[i].getterCallback(fieldName, initialValue);
            if (handlerResult != initialValue) { result = handlerResult; }
        }

        if (result != null) {
            Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] getterCallbackMethod return for " + fieldName +  " -> " + result);
            return result;
        } else {
            Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] getterCallbackMethod return for " + fieldName +  " -> " + initialValue);
            return initialValue;
        }
    }

    /**
     * This method is called by the manipulated class each time that a PUTFILED instruction is found.
     * the method send to each handler the new value.
     * @param fieldName : the field name on which the PUTFIELD instruction is called
     * @param objectValue : the value of the field
     */
    public void setterCallback(String fieldName, Object objectValue) {
        Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] Call the setterCallbackMethod on " + fieldName +  " with " + objectValue);
        // Get the list of registered handlers
        Handler[] list = (Handler[]) m_fieldRegistration.get(fieldName);

        for (int i = 0; list != null && i < list.length; i++) {
            m_handlers[i].setterCallback(fieldName, objectValue);
        }
    }

    /**
     * @return the context of the component.
     */
    public BundleContext getContext() { return m_context; }

    /**
     * Check the state of all handlers.
     */
    public void checkComponentState() {
        Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] Check the component state");
        boolean isValid = true;
        for (int i = 0; i < m_handlers.length; i++) {
            boolean b = m_handlers[i].isValid();
            Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] Validity of the handler : " + m_handlers[i] + " = " + b);
            isValid = isValid && b;
        }

        // Update the component state if necessary
        if (!isValid && m_state == VALID) {
            // Need to update the state to UNRESOLVED
            setState(INVALID);
            m_instances = new Object[0];
            return;
        }
        if (isValid && m_state == INVALID) {
            setState(VALID);
            if (m_metadata.isImmediate() && m_instances.length == 0) { createInstance(); }
        }

        Activator.getLogger().log(Level.INFO, "[" + m_metadata.getClassName() + "] Component Manager : " + m_state);
    }


    // ======================= end Handlers Management =====================

}
