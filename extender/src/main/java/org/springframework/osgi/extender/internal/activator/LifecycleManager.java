/*
 * Copyright 2006-2009 the original author or authors.
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

package org.springframework.osgi.extender.internal.activator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.osgi.context.ConfigurableOsgiBundleApplicationContext;
import org.springframework.osgi.context.DelegatedExecutionOsgiBundleApplicationContext;
import org.springframework.osgi.context.event.OsgiBundleApplicationContextEventMulticaster;
import org.springframework.osgi.extender.OsgiApplicationContextCreator;
import org.springframework.osgi.extender.OsgiBeanFactoryPostProcessor;
import org.springframework.osgi.extender.internal.dependencies.shutdown.BundleDependencyComparator;
import org.springframework.osgi.extender.internal.dependencies.shutdown.ComparatorServiceDependencySorter;
import org.springframework.osgi.extender.internal.dependencies.shutdown.ServiceDependencySorter;
import org.springframework.osgi.extender.internal.dependencies.startup.DependencyWaiterApplicationContextExecutor;
import org.springframework.osgi.extender.internal.support.ExtenderConfiguration;
import org.springframework.osgi.extender.internal.support.LazyLatchFactoryDelegate;
import org.springframework.osgi.extender.internal.support.OsgiBeanFactoryPostProcessorAdapter;
import org.springframework.osgi.extender.internal.util.concurrent.Counter;
import org.springframework.osgi.extender.internal.util.concurrent.RunnableTimedExecution;
import org.springframework.osgi.extender.support.ApplicationContextConfiguration;
import org.springframework.osgi.extender.support.internal.ConfigUtils;
import org.springframework.osgi.service.exporter.support.OsgiServiceFactoryBean;
import org.springframework.osgi.util.OsgiBundleUtils;
import org.springframework.osgi.util.OsgiStringUtils;

/**
 * Manager handling the startup/shutdown threading issues regarding OSGi
 * contexts. Used by {@link ContextLoaderListener}.
 * 
 * 
 * @author Costin Leau
 */
class LifecycleManager implements DisposableBean {

	/**
	 * Holder class providing lazy loading-related information for managed OSGi
	 * contexts.
	 * 
	 * @author Costin Leau
	 */
	private static class ContextLazyInfo {

		/** executor */
		volatile DependencyWaiterApplicationContextExecutor executor;
		final Integer contextHash;


		ContextLazyInfo(Integer hash) {
			this.contextHash = hash;
		}

		/** activate bundle */
		void eagerLoading() {
			// remove any existing latches
			LazyLatchFactoryDelegate.removeLatch(contextHash);

			// if the bundle is waiting for dependencies
			// activate it
			if (executor != null) {
				executor.activate();
			}
		}
	}

	/**
	 * Task executed for lazy activated bundles.
	 * 
	 * @author Costin Leau
	 */
	private class LazyActivationTask extends CompleteRefreshTask {

		private final String EXPORTER_CLASS_NAME = OsgiServiceFactoryBean.class.getName();


		public LazyActivationTask(ConfigurableOsgiBundleApplicationContext applicationContext) {
			super(applicationContext);
		}

		public void run() {
			super.run();
			// discover the exporters
			ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
			String[] names = beanFactory.getBeanDefinitionNames();
			for (String name : names) {
				BeanDefinition definition = beanFactory.getBeanDefinition(name);
				if (EXPORTER_CLASS_NAME.equals(definition.getBeanClassName())) {
					beanFactory.getBean(name);
				}
			}
		}
	}

	/**
	 * Task executed for completing the application context refresh.
	 * 
	 * @author Costin Leau
	 */
	private class CompleteRefreshTask implements Runnable {

		protected final ConfigurableOsgiBundleApplicationContext applicationContext;


		public CompleteRefreshTask(ConfigurableOsgiBundleApplicationContext applicationContext) {
			this.applicationContext = applicationContext;
		}

		public void run() {
			// post refresh events are caught through events
			processor.preProcessRefresh(applicationContext);
			applicationContext.refresh();
		}
	}


	/** logger */
	private static final Log log = LogFactory.getLog(LifecycleManager.class);

	/**
	 * The contexts we are currently managing. Keys are bundle ids, values are
	 * ServiceDependentOsgiApplicationContexts for the application context
	 */
	private final Map<Long, ConfigurableOsgiBundleApplicationContext> managedContexts = new ConcurrentHashMap<Long, ConfigurableOsgiBundleApplicationContext>(
		16);

	private final Map<ConfigurableOsgiBundleApplicationContext, ContextLazyInfo> contextLazyInfo = new ConcurrentHashMap<ConfigurableOsgiBundleApplicationContext, ContextLazyInfo>(
		16);

	/** listener counter - used to properly synchronize shutdown */
	private Counter contextsStarted = new Counter("contextsStarted");

	// "Spring Application Context Creation Timer"
	private final Timer timer = new Timer("Spring DM Context Creation Timer", true);

	/** Task executor used for bootstraping the Spring contexts in async mode */
	private final TaskExecutor taskExecutor;

	/** ApplicationContext Creator */
	private final OsgiApplicationContextCreator contextCreator;

	/** BFPP list */
	private final List<OsgiBeanFactoryPostProcessor> postProcessors;

	/** shutdown task executor */
	private final TaskExecutor shutdownTaskExecutor;

	/**
	 * Task executor which uses the same thread for running tasks. Used when
	 * doing a synchronous wait-for-dependencies.
	 */
	private final TaskExecutor sameThreadTaskExecutor = new SyncTaskExecutor();

	/** Service-based dependency sorter for shutdown */
	private final ServiceDependencySorter shutdownDependencySorter = new ComparatorServiceDependencySorter();

	private final OsgiBundleApplicationContextEventMulticaster multicaster;

	private final ExtenderConfiguration extenderConfiguration;

	private final BundleContext bundleContext;

	private final OsgiContextProcessor processor;

	private final ApplicationContextConfigurationFactory contextConfigurationFactory;

	private final VersionMatcher versionMatcher;


	LifecycleManager(ExtenderConfiguration extenderConfiguration, VersionMatcher versionMatcher,
			ApplicationContextConfigurationFactory appCtxCfgFactory, OsgiContextProcessor processor,
			BundleContext context) {

		this.versionMatcher = versionMatcher;
		this.extenderConfiguration = extenderConfiguration;
		this.contextConfigurationFactory = appCtxCfgFactory;

		this.processor = processor;

		this.taskExecutor = extenderConfiguration.getTaskExecutor();
		this.shutdownTaskExecutor = extenderConfiguration.getShutdownTaskExecutor();

		this.multicaster = extenderConfiguration.getEventMulticaster();

		this.contextCreator = extenderConfiguration.getContextCreator();
		this.postProcessors = extenderConfiguration.getPostProcessors();

		this.bundleContext = context;
	}

	/**
	 * Context creation is a potentially long-running activity (certainly more
	 * than we want to do on the synchronous event callback).
	 * 
	 * <p/>
	 * Based on our configuration, the context can be started on the same thread
	 * or on a different one.
	 * 
	 * <p/>
	 * Kick off a background activity to create an application context for the
	 * given bundle if needed.
	 * 
	 * <b>Note:</b> Make sure to do the fastest filtering first to avoid
	 * slow-downs on platforms with a big number of plugins and wiring (i.e.
	 * Eclipse platform).
	 * 
	 * @param bundle target bundle
	 * @param isLazy if the bundle is lazily activated
	 */
	protected void maybeCreateApplicationContextFor(Bundle bundle, boolean isLazy) {

		boolean debug = log.isDebugEnabled();
		String bundleString = (debug ? "[" + OsgiStringUtils.nullSafeNameAndSymName(bundle) + "]" : null);

		final Long bundleId = new Long(bundle.getBundleId());

		ConfigurableOsgiBundleApplicationContext context = managedContexts.get(bundleId);

		if (context != null) {
			// check if a lazy bundle was started...
			ContextLazyInfo lazyInfo;
			if (!isLazy && (lazyInfo = contextLazyInfo.remove(context)) != null) {
				// no more lazy loading
				lazyInfo.eagerLoading();
				if (debug) {
					log.debug("Lazy bundle " + bundleString + " was started");
				}
			}
			else {
				if (debug) {
					log.debug("Bundle " + bundleString + " is already managed; ignoring...");
				}
			}
			return;
		}

		if (!versionMatcher.matchVersion(bundle)) {
			return;
		}

		BundleContext localBundleContext = OsgiBundleUtils.getBundleContext(bundle);

		if (debug)
			log.debug("Scanning" + (isLazy ? " lazy" : "") + " bundle " + bundleString + " for configurations...");

		// initialize context
		final DelegatedExecutionOsgiBundleApplicationContext localApplicationContext;

		if (debug)
			log.debug("Creating an application context for " + (isLazy ? " lazy" : "") + " bundle " + bundleString);

		try {
			localApplicationContext = contextCreator.createApplicationContext(localBundleContext);
		}
		catch (Exception ex) {
			log.error("Cannot create application context for bundle " + bundleString, ex);
			return;
		}

		if (localApplicationContext == null) {
			log.debug("No application context created for bundle " + bundleString);
			return;
		}

		// create a dedicated hook for this application context
		BeanFactoryPostProcessor processingHook = new OsgiBeanFactoryPostProcessorAdapter(localBundleContext,
			postProcessors);

		// add in the osgi post processors
		localApplicationContext.addBeanFactoryPostProcessor(processingHook);

		// add the context to the tracker
		managedContexts.put(bundleId, localApplicationContext);

		localApplicationContext.setDelegatedEventMulticaster(multicaster);

		ApplicationContextConfiguration config = contextConfigurationFactory.createConfiguration(bundle);

		TaskExecutor executor = configureExecutor(config, debug, bundleString);

		DependencyWaiterApplicationContextExecutor contextExecutor = configureDependencyWaiting(
			localApplicationContext, config, executor, isLazy, debug, bundleString);

		Runnable task = new CompleteRefreshTask(localApplicationContext);

		// check lazy initialization
		if (isLazy) {
			// save context
			Integer hash = Integer.valueOf(System.identityHashCode(localApplicationContext));
			final ContextLazyInfo info = new ContextLazyInfo(hash);
			info.executor = contextExecutor;
			contextLazyInfo.put(localApplicationContext, info);
			// add latch
			LazyLatchFactoryDelegate.addLatch(hash);
			task = new LazyActivationTask(localApplicationContext);
		}

		executor.execute(task);
	}

	private TaskExecutor configureExecutor(ApplicationContextConfiguration config, boolean debug, String bundleString) {
		// executor used for creating the appCtx
		// chosen based on the sync/async configuration
		TaskExecutor executor = null;

		String creationType;

		final boolean asynch = config.isCreateAsynchronously();

		// synch/asynch context creation
		if (asynch) {
			// for the async stuff use the executor
			executor = taskExecutor;
			creationType = "Asynchronous";
		}
		else {
			// for the sync stuff, use this thread
			executor = sameThreadTaskExecutor;
			creationType = "Synchronous";
		}

		if (debug) {
			log.debug(creationType + " context creation for bundle " + bundleString);
		}

		return executor;
	}

	private DependencyWaiterApplicationContextExecutor configureDependencyWaiting(
			DelegatedExecutionOsgiBundleApplicationContext context, ApplicationContextConfiguration config,
			TaskExecutor executor, boolean isLazy, boolean debug, String bundleString) {
		Bundle bundle = context.getBundle();

		// wait/no wait for dependencies behaviour
		if (config.isWaitForDependencies()) {
			DependencyWaiterApplicationContextExecutor appCtxExecutor = new DependencyWaiterApplicationContextExecutor(
				context, !config.isCreateAsynchronously(), extenderConfiguration.getDependencyFactories(), isLazy);

			long timeout;
			// check whether a timeout has been defined

			if (ConfigUtils.isDirectiveDefined(bundle.getHeaders(), ConfigUtils.DIRECTIVE_TIMEOUT)) {
				timeout = config.getTimeout();
				if (debug)
					log.debug("Setting bundle-defined, wait-for-dependencies timeout value=" + timeout
							+ " ms, for bundle " + bundleString);

			}
			else {
				timeout = extenderConfiguration.getDependencyWaitTime();
				if (debug)
					log.debug("Setting globally defined wait-for-dependencies timeout value=" + timeout
							+ " ms, for bundle " + bundleString);
			}

			appCtxExecutor.setTimeout(config.getTimeout());
			appCtxExecutor.setWatchdog(timer);
			appCtxExecutor.setTaskExecutor(executor);
			appCtxExecutor.setMonitoringCounter(contextsStarted);
			appCtxExecutor.setDelegatedMulticaster(this.multicaster);

			contextsStarted.increment();

			return appCtxExecutor;
		}
		else {
			return null;
		}
	}

	/**
	 * Closing an application context is a potentially long-running activity,
	 * however, we *have* to do it synchronously during the event process as the
	 * BundleContext object is not valid once we return from this method.
	 * 
	 * @param bundle target bundle
	 */
	protected void maybeCloseApplicationContextFor(Bundle bundle) {
		final ConfigurableOsgiBundleApplicationContext context = managedContexts.remove(Long.valueOf(bundle.getBundleId()));
		if (context == null) {
			return;
		}

		RunnableTimedExecution.execute(new Runnable() {

			private final String toString = "Closing runnable for context " + context.getDisplayName();


			public void run() {
				closeApplicationContext(context);
			}

			public String toString() {
				return toString;
			}

		}, extenderConfiguration.getShutdownWaitTime(), shutdownTaskExecutor);
	}

	/**
	 * Closes an application context. This is a convenience methods that invokes
	 * the event notification as well.
	 * 
	 * @param ctx
	 */
	private void closeApplicationContext(ConfigurableOsgiBundleApplicationContext ctx) {
		if (log.isDebugEnabled())
			log.debug("Closing application context " + ctx.getDisplayName());

		processor.preProcessClose(ctx);
		try {
			ctx.close();
		}
		finally {
			processor.postProcessClose(ctx);
		}
	}

	public void destroy() {
		// first stop the watchdog
		stopTimer();

		// cleanup lazy latches
		LazyLatchFactoryDelegate.clear();

		// destroy bundles
		Bundle[] bundles = new Bundle[managedContexts.size()];

		int i = 0;
		for (Iterator<ConfigurableOsgiBundleApplicationContext> it = managedContexts.values().iterator(); it.hasNext();) {
			ConfigurableOsgiBundleApplicationContext context = it.next();
			bundles[i++] = context.getBundle();
		}

		bundles = shutdownDependencySorter.computeServiceDependencyGraph(bundles);

		boolean debug = log.isDebugEnabled();

		StringBuilder buffer = new StringBuilder();

		if (debug) {
			buffer.append("Shutdown order is: {");
			for (i = 0; i < bundles.length; i++) {
				buffer.append("\nBundle [" + bundles[i].getSymbolicName() + "]");
				ServiceReference[] services = bundles[i].getServicesInUse();
				Set<Bundle> usedBundles = new LinkedHashSet<Bundle>();
				if (services != null) {
					for (int j = 0; j < services.length; j++) {
						if (BundleDependencyComparator.isSpringManagedService(services[j])) {
							Bundle used = services[j].getBundle();
							if (!used.equals(bundleContext.getBundle()) && !usedBundles.contains(used)) {
								usedBundles.add(used);
								buffer.append("\n  Using [" + used.getSymbolicName() + "]");
							}
						}

					}
				}
			}
			buffer.append("\n}");
			log.debug(buffer);
		}

		final List<Runnable> taskList = new ArrayList<Runnable>(managedContexts.size());
		final List<ConfigurableOsgiBundleApplicationContext> closedContexts = Collections.synchronizedList(new ArrayList<ConfigurableOsgiBundleApplicationContext>());
		final Object[] contextClosingDown = new Object[1];

		for (i = 0; i < bundles.length; i++) {
			Long id = new Long(bundles[i].getBundleId());
			final ConfigurableOsgiBundleApplicationContext context = (ConfigurableOsgiBundleApplicationContext) managedContexts.get(id);
			if (context != null) {
				closedContexts.add(context);
				// add a new runnable
				taskList.add(new Runnable() {

					private final String toString = "Closing runnable for context " + context.getDisplayName();


					public void run() {
						contextClosingDown[0] = context;
						// eliminate context
						closedContexts.remove(context);
						closeApplicationContext(context);
					}

					public String toString() {
						return toString;
					}
				});
			}
		}

		// tasks
		final Runnable[] tasks = (Runnable[]) taskList.toArray(new Runnable[taskList.size()]);

		// start the ripper >:)
		for (int j = 0; j < tasks.length; j++) {
			if (RunnableTimedExecution.execute(tasks[j], extenderConfiguration.getShutdownWaitTime(),
				shutdownTaskExecutor)) {
				if (debug) {
					log.debug(contextClosingDown[0] + " context did not close successfully; forcing shutdown...");
				}
			}
		}

		this.managedContexts.clear();

		// before bailing out; wait for the threads that might be left by
		// the task executor
		stopTaskExecutor();
	}

	/**
	 * Do some additional waiting so the service dependency listeners detect the
	 * shutdown.
	 */
	private void stopTaskExecutor() {
		boolean debug = log.isDebugEnabled();

		if (debug)
			log.debug("Waiting for " + contextsStarted + " service dependency listener(s) to stop...");

		contextsStarted.waitForZero(extenderConfiguration.getShutdownWaitTime());

		if (!contextsStarted.isZero()) {
			if (debug)
				log.debug(contextsStarted.getValue()
						+ " service dependency listener(s) did not responded in time; forcing them to shutdown...");
			extenderConfiguration.setForceThreadShutdown(true);
		}

		else
			log.debug("All listeners closed");
	}

	/**
	 * Cancel any tasks scheduled for the timer.
	 */
	private void stopTimer() {
		if (log.isDebugEnabled())
			log.debug("Canceling timer tasks");
		timer.cancel();
	}
}