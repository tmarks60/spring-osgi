/*
 * Copyright 2002-2006 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.osgi.service.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.osgi.service.TargetSourceLifecycleListener;
import org.springframework.osgi.service.importer.ReferenceClassLoadingOptions;
import org.springframework.osgi.service.interceptor.OsgiServiceInvoker;
import org.springframework.osgi.service.interceptor.OsgiServiceStaticInterceptor;
import org.springframework.osgi.service.interceptor.ServiceReferenceAwareAdvice;
import org.springframework.osgi.service.util.OsgiServiceBindingUtils;
import org.springframework.osgi.util.OsgiListenerUtils;
import org.springframework.util.Assert;

/**
 * OSGi service dynamic collection - allows iterating while the underlying
 * storage is being shrunk/expanded. This collection is read-only - its content
 * is being retrieved dynamically from the OSGi platform.
 * 
 * <p/> This collection and its iterators are thread-safe. That is, multiple
 * threads can access the collection. However, since the collection is read-only,
 * it cannot be modified by the client.
 * 
 * @see Collection
 * @author Costin Leau
 */
public class OsgiServiceCollection implements Collection, InitializingBean {

	/**
	 * Listener tracking the OSGi services which form the dynamic collection.
	 * 
	 * @author Costin Leau
	 */
	private class Listener implements ServiceListener {

		public void serviceChanged(ServiceEvent event) {
			ClassLoader tccl = Thread.currentThread().getContextClassLoader();

			try {
				Thread.currentThread().setContextClassLoader(classLoader);
				ServiceReference ref = event.getServiceReference();
				Long serviceId = (Long) ref.getProperty(Constants.SERVICE_ID);
				boolean found = false;

				switch (event.getType()) {

				case (ServiceEvent.REGISTERED):
				case (ServiceEvent.MODIFIED):
					// same as ServiceEvent.REGISTERED
					synchronized (serviceIDs) {
						if (!serviceReferences.containsKey(serviceId)) {
							found = true;
							serviceReferences.put(serviceId, createServiceProxy(ref));
							serviceIDs.add(serviceId);
						}
					}
					// inform listeners
					// TODO: should this be part of the lock also?
					if (found)
						OsgiServiceBindingUtils.callListenersBind(context, ref, listeners);

					break;
				case (ServiceEvent.UNREGISTERING):
					synchronized (serviceIDs) {
						// remove servce
						Object proxy = serviceReferences.remove(serviceId);
						found = serviceIDs.remove(serviceId);
						if (proxy != null) {
							invalidateProxy(proxy);
							lastDeadProxy = proxy;
						}
					}
					// TODO: should this be part of the lock also?
					if (found)
						OsgiServiceBindingUtils.callListenersUnbind(context, ref, listeners);
					break;

				default:
					throw new IllegalArgumentException("unsupported event type:" + event);
				}
			}
			// OSGi swallows these exceptions so make sure we get a chance to
			// see them.
			catch (Throwable re) {
				if (log.isWarnEnabled()) {
					log.warn("serviceChanged() processing failed", re);
				}
			}
			finally {
				Thread.currentThread().setContextClassLoader(tccl);
			}
		}
	}

	private static final Log log = LogFactory.getLog(OsgiServiceCollection.class);

	// map of services
	// the service id is used as key while the service proxy is used for
	// values
	// Map<ServiceId, ServiceProxy>
	// 
	// NOTE: this collection is protected by the 'serviceIDs' lock.
	protected final Map serviceReferences = new LinkedHashMap(8);

	/**
	 * list binding the service IDs to the map of service proxies
	 */
	protected final Collection serviceIDs = createInternalDynamicStorage();

	/**
	 * Recall the last proxy for the rare case, where a service goes down
	 * between the #hasNext() and #next() call of an iterator.
	 */
	protected volatile Object lastDeadProxy;

	private final Filter filter;

	private final BundleContext context;

	private int contextClassLoader = ReferenceClassLoadingOptions.CLIENT;

	protected final ClassLoader classLoader;

	// advices to be applied when creating service proxy
	private Object[] interceptors = new Object[0];

	private TargetSourceLifecycleListener[] listeners = new TargetSourceLifecycleListener[0];

	private AdvisorAdapterRegistry advisorAdapterRegistry = GlobalAdvisorAdapterRegistry.getInstance();

	public OsgiServiceCollection(Filter filter, BundleContext context, ClassLoader classLoader) {
		Assert.notNull(classLoader, "ClassLoader is required");
		Assert.notNull(context, "context is required");

		this.filter = filter;
		this.context = context;
		this.classLoader = classLoader;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	public void afterPropertiesSet() {
		if (log.isTraceEnabled())
			log.trace("adding osgi listener for services matching [" + filter + "]");
		OsgiListenerUtils.addServiceListener(context, new Listener(), filter);
	}

	/**
	 * Create the dynamic storage used internally. The storage <strong>has</strong>
	 * to be thread-safe.
	 */
	protected Collection createInternalDynamicStorage() {
		return new DynamicCollection();
	}

	/**
	 * Create a service proxy over the service reference. The proxy purpose is
	 * to transparently decouple the client from holding a strong reference to
	 * the service (which might go away).
	 * 
	 * @param ref
	 */
	private Object createServiceProxy(ServiceReference ref) {
		// get classes under which the service was registered
		String[] classes = (String[]) ref.getProperty(Constants.OBJECTCLASS);

		List intfs = new ArrayList();
		Class proxyClass = null;

		for (int i = 0; i < classes.length; i++) {
			// resolve classes (using the proper classloader)
			Bundle loader = ref.getBundle();
			try {
				Class clazz = loader.loadClass(classes[i]);
				// FIXME: discover lower class if multiple class names are used
				// (basically detect the lowest subclass)
				if (clazz.isInterface())
					intfs.add(clazz);
				else {
					proxyClass = clazz;
				}
			}
			catch (ClassNotFoundException cnfex) {
				throw (RuntimeException) new IllegalArgumentException("cannot create proxy").initCause(cnfex);
			}
		}

		ProxyFactory factory = new ProxyFactory();
		if (!intfs.isEmpty())
			factory.setInterfaces((Class[]) intfs.toArray(new Class[intfs.size()]));

		if (proxyClass != null) {
			factory.setProxyTargetClass(true);
			factory.setTargetClass(proxyClass);
		}

		// add the interceptors
		if (this.interceptors != null) {
			for (int i = 0; i < this.interceptors.length; i++) {
				factory.addAdvisor(this.advisorAdapterRegistry.wrap(this.interceptors[i]));
			}
		}

		addEndingInterceptors(factory, ref);

		// TODO: why not add these?
		// factory.setOptimize(true);
		// factory.setFrozen(true);

		return factory.getProxy(classLoader);
	}

	/**
	 * Add the ending interceptors such as lookup and Service Reference aware.
	 * @param pf
	 */
	protected void addEndingInterceptors(ProxyFactory factory, ServiceReference ref) {
		OsgiServiceInvoker invoker = new OsgiServiceStaticInterceptor(context, ref, contextClassLoader, classLoader);
		factory.addAdvice(new ServiceReferenceAwareAdvice(invoker));
		factory.addAdvice(invoker);
	}

	private void invalidateProxy(Object proxy) {
		// TODO: add proxy invalidation
	}

	public Iterator iterator() {
		// use the service map not just the list of indexes
		return new Iterator() {
			// dynamic thread-safe iterator
			private final Iterator iter = serviceIDs.iterator();

			public boolean hasNext() {
				return iter.hasNext();
			}

			public Object next() {
				synchronized (serviceIDs) {
					Object id = iter.next();
					return (id == null ? lastDeadProxy : serviceReferences.get(id));
				}
			}

			public void remove() {
				// write operations disabled
				throw new UnsupportedOperationException();
			}
		};
	}

	public int size() {
		return serviceIDs.size();
	}

	public String toString() {
		synchronized (serviceIDs) {
			return serviceReferences.values().toString();
		}
	}

	//
	// write operations forbidden
	//
	public boolean remove(Object o) {
		throw new UnsupportedOperationException();
	}

	public boolean removeAll(Collection c) {
		throw new UnsupportedOperationException();
	}

	public boolean add(Object o) {
		throw new UnsupportedOperationException();
	}

	public boolean addAll(Collection c) {
		throw new UnsupportedOperationException();
	}

	public void clear() {
		throw new UnsupportedOperationException();
	}

	public boolean retainAll(Collection c) {
		throw new UnsupportedOperationException();
	}

	public boolean contains(Object o) {
		synchronized (serviceIDs) {
			return serviceReferences.containsValue(o);
		}
	}

	public boolean containsAll(Collection c) {
		synchronized (serviceIDs) {
			return serviceReferences.values().containsAll(c);
		}
	}

	public boolean isEmpty() {
		return size() == 0;
	}

	public Object[] toArray() {
		synchronized (serviceIDs) {
			return serviceReferences.values().toArray();
		}
	}

	public Object[] toArray(Object[] array) {
		synchronized (serviceIDs) {
			return serviceReferences.values().toArray(array);
		}
	}

	/**
	 * @return Returns the interceptors.
	 */
	public Object[] getInterceptors() {
		return interceptors;
	}

	/**
	 * The optional interceptors used when proxying each service. These will
	 * always be added before the OsgiServiceStaticInterceptor.
	 * 
	 * @param interceptors The interceptors to set.
	 */
	public void setInterceptors(Object[] interceptors) {
		Assert.notNull(interceptors, "argument should not be null");
		this.interceptors = interceptors;
	}

	/**
	 * @param listeners The listeners to set.
	 */
	public void setListeners(TargetSourceLifecycleListener[] listeners) {
		Assert.notNull(listeners, "argument should not be null");
		this.listeners = listeners;
	}

	/**
	 * @param contextClassLoader The contextClassLoader to set.
	 */
	public void setContextClassLoader(int contextClassLoader) {
		this.contextClassLoader = contextClassLoader;
	}
}
