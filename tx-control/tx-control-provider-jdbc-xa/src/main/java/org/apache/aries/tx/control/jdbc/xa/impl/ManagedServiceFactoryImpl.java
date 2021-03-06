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
 * "AS IS" BASIS, WITHOUT WARRANTIESOR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.tx.control.jdbc.xa.impl;

import static java.util.Arrays.asList;
import static org.apache.aries.tx.control.jdbc.xa.impl.JDBCConnectionProviderFactoryImpl.toBoolean;
import static org.osgi.framework.Constants.OBJECTCLASS;
import static org.osgi.service.jdbc.DataSourceFactory.JDBC_DATABASE_NAME;
import static org.osgi.service.jdbc.DataSourceFactory.JDBC_DATASOURCE_NAME;
import static org.osgi.service.jdbc.DataSourceFactory.JDBC_DESCRIPTION;
import static org.osgi.service.jdbc.DataSourceFactory.JDBC_NETWORK_PROTOCOL;
import static org.osgi.service.jdbc.DataSourceFactory.JDBC_PASSWORD;
import static org.osgi.service.jdbc.DataSourceFactory.JDBC_PORT_NUMBER;
import static org.osgi.service.jdbc.DataSourceFactory.JDBC_ROLE_NAME;
import static org.osgi.service.jdbc.DataSourceFactory.JDBC_SERVER_NAME;
import static org.osgi.service.jdbc.DataSourceFactory.JDBC_URL;
import static org.osgi.service.jdbc.DataSourceFactory.JDBC_USER;
import static org.osgi.service.jdbc.DataSourceFactory.OSGI_JDBC_DRIVER_CLASS;
import static org.osgi.service.transaction.control.jdbc.JDBCConnectionProviderFactory.OSGI_RECOVERY_IDENTIFIER;
import static org.osgi.service.transaction.control.jdbc.JDBCConnectionProviderFactory.XA_ENLISTMENT_ENABLED;

import java.util.Arrays;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.aries.tx.control.jdbc.common.impl.AbstractJDBCConnectionProvider;
import org.apache.aries.tx.control.resource.common.impl.ConfigurationDefinedResourceFactory;
import org.apache.aries.tx.control.resource.common.impl.LifecycleAware;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.jdbc.DataSourceFactory;
import org.osgi.service.transaction.control.jdbc.JDBCConnectionProvider;
import org.osgi.service.transaction.control.recovery.RecoverableXAResource;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManagedServiceFactoryImpl extends ConfigurationDefinedResourceFactory {

	private static final Logger LOG = LoggerFactory.getLogger(ManagedServiceFactoryImpl.class);
	
	private static final String DSF_TARGET_FILTER = "aries.dsf.target.filter";
	private static final String JDBC_PROP_NAMES = "aries.jdbc.property.names";
	private static final List<String> JDBC_PROPERTIES = asList(JDBC_DATABASE_NAME, JDBC_DATASOURCE_NAME,
			JDBC_DESCRIPTION, JDBC_NETWORK_PROTOCOL, JDBC_PASSWORD, JDBC_PORT_NUMBER, JDBC_ROLE_NAME, JDBC_SERVER_NAME,
			JDBC_URL, JDBC_USER);

	public ManagedServiceFactoryImpl(BundleContext context) {
		super(context);
	}

	@Override
	public String getName() {
		return "Aries JDBCConnectionProvider (XA enabled) service";
	}

	@Override
	protected LifecycleAware getConfigurationDrivenResource(BundleContext context, 
			String pid, Map<String, Object> properties) throws Exception {
		Properties jdbcProps = getJdbcProps(pid, properties);
	
		try {
			return new ManagedJDBCResourceProvider(context, pid, jdbcProps, properties);
		} catch (InvalidSyntaxException e) {
			LOG.error("The configuration {} contained an invalid target filter {}", pid, e.getFilter());
			throw new ConfigurationException(DSF_TARGET_FILTER, "The target filter was invalid", e);
		}
	}

	@SuppressWarnings("unchecked")
	private Properties getJdbcProps(String pid, Map<String, Object> properties) throws ConfigurationException {

		Object object = properties.getOrDefault(JDBC_PROP_NAMES, JDBC_PROPERTIES);
		Collection<String> propnames;
		if (object instanceof String) {
			propnames = Arrays.asList(((String) object).split(","));
		} else if (object instanceof String[]) {
			propnames = Arrays.asList((String[]) object);
		} else if (object instanceof Collection) {
			propnames = (Collection<String>) object;
		} else {
			LOG.error("The configuration {} contained an invalid list of JDBC property names", pid, object);
			throw new ConfigurationException(JDBC_PROP_NAMES,
					"The jdbc property names must be a String+ or comma-separated String");
		}

		Properties p = new Properties();

		propnames.stream().filter(properties::containsKey)
				.forEach(s -> p.setProperty(s, String.valueOf(properties.get(s))));

		return p;
	}

	private static class ManagedJDBCResourceProvider
			implements ServiceTrackerCustomizer<DataSourceFactory, DataSourceFactory>, LifecycleAware {

		private final BundleContext context;
		private final String pid;
		private final Properties jdbcProperties;
		private final Map<String, Object> providerProperties;
		private final ServiceTracker<DataSourceFactory, DataSourceFactory> dsfTracker;

		private DataSourceFactory activeDsf;
		private ServiceRegistration<JDBCConnectionProvider> serviceReg;
		private ServiceRegistration<RecoverableXAResource> recoveryReg;
		private AbstractJDBCConnectionProvider provider;

		public ManagedJDBCResourceProvider(BundleContext context, String pid, Properties jdbcProperties,
				Map<String, Object> providerProperties) throws InvalidSyntaxException, ConfigurationException {
			this.context = context;
			this.pid = pid;
			this.jdbcProperties = jdbcProperties;
			this.providerProperties = providerProperties;

			String targetFilter = (String) providerProperties.get(DSF_TARGET_FILTER);
			if (targetFilter == null) {
				String driver = (String) providerProperties.get(OSGI_JDBC_DRIVER_CLASS);
				if (driver == null) {
					LOG.error("The configuration {} must specify a target filter or a JDBC driver class", pid);
					throw new ConfigurationException(OSGI_JDBC_DRIVER_CLASS,
							"The configuration must specify either a target filter or a JDBC driver class");
				}
				targetFilter = "(&(" + OBJECTCLASS + "=" + DataSourceFactory.class.getName() + ")(" + OSGI_JDBC_DRIVER_CLASS + "=" + driver + "))";
			}

			targetFilter = "(&(" + OBJECTCLASS + "=" + DataSourceFactory.class.getName() + ")" + targetFilter + ")";

			this.dsfTracker = new ServiceTracker<>(context, context.createFilter(targetFilter), this);
		}

		public void start() {
			dsfTracker.open();
		}

		public void stop() {
			dsfTracker.close();
		}

		@Override
		public DataSourceFactory addingService(ServiceReference<DataSourceFactory> reference) {
			DataSourceFactory service = context.getService(reference);
			return updateService(service);
		}

		private DataSourceFactory updateService(DataSourceFactory service) {
			boolean setDsf;
			synchronized (this) {
				setDsf = activeDsf == null;
				if(setDsf)
					activeDsf = service;
			}

			ServiceRegistration<JDBCConnectionProvider> reg = null;
			ServiceRegistration<RecoverableXAResource> reg2 = null;
			JDBCConnectionProviderImpl provider = null;
			if (setDsf) {
				try {
					provider = new JDBCConnectionProviderFactoryImpl().getProviderFor(service,
							jdbcProperties, providerProperties);
					String recoveryId = (String) providerProperties.get(OSGI_RECOVERY_IDENTIFIER);
					if(recoveryId !=null) {
						if(toBoolean(providerProperties, XA_ENLISTMENT_ENABLED, true)) {
							LOG.warn("A JDBCResourceProvider has been configured with a recovery identifier {} but it has also been configured not to use XA transactions. No recovery will be available.", recoveryId);
						} else {
							reg2 = context.registerService(RecoverableXAResource.class, 
									new RecoverableXAResourceImpl(recoveryId, provider, 
											(String) providerProperties.get("recovery.user"),
											(String) providerProperties.get(".recovery.password)")), 
									getServiceProperties());
						}
					}
					reg = context
							.registerService(JDBCConnectionProvider.class, provider, getServiceProperties());

					ServiceRegistration<JDBCConnectionProvider> oldReg;
					ServiceRegistration<RecoverableXAResource> oldReg2;
					AbstractJDBCConnectionProvider oldProvider;
					synchronized (this) {
						if(activeDsf == service) {
							oldReg = serviceReg;
							serviceReg = reg;
							oldReg2 = recoveryReg;
							recoveryReg = reg2;
							oldProvider = this.provider;
							this.provider = provider;
						} else {
							oldReg = reg;
							oldReg2 = reg2;
							oldProvider = provider;
						}
					}
					safeUnregister(oldReg);
					safeUnregister(oldReg2);
					safeClose(oldProvider);
				} catch (Exception e) {
					LOG.error("An error occurred when creating the connection provider for {}.", pid, e);
					
					synchronized (this) {
						if(activeDsf == service) {
							activeDsf = null;
						}
					}
					safeUnregister(reg);
					safeUnregister(reg2);
					safeClose(provider);
				}
			}
			return service;
		}

		private void safeUnregister(ServiceRegistration<?> reg) {
			if(reg != null) {
				try {
					reg.unregister();
				} catch (IllegalStateException ise) {
					LOG.debug("An exception occurred when unregistering a service for {}", pid);
				}
			}
		}
			
		private void safeClose(AbstractJDBCConnectionProvider oldProvider) {
			if(oldProvider != null) {
				try {
					oldProvider.close();
				} catch (Exception e) {
					LOG.debug("An exception occurred when closing a provider for {}", pid, e);
				}
			}
		}

		private Dictionary<String, ?> getServiceProperties() {
			Hashtable<String, Object> props = new Hashtable<>();
			providerProperties.keySet().stream()
					.filter(s -> !s.startsWith("."))
					.filter(s -> !JDBC_PASSWORD.equals(s))
					.forEach(s -> props.put(s, providerProperties.get(s)));
			return props;
		}

		@Override
		public void modifiedService(ServiceReference<DataSourceFactory> reference, DataSourceFactory service) {
		}

		@Override
		public void removedService(ServiceReference<DataSourceFactory> reference, DataSourceFactory service) {
			boolean dsfLeft;
			ServiceRegistration<JDBCConnectionProvider> oldReg = null;
			ServiceRegistration<RecoverableXAResource> oldReg2 = null;
			AbstractJDBCConnectionProvider oldProvider = null;
			synchronized (this) {
				dsfLeft = activeDsf == service;
				if (dsfLeft) {
					activeDsf = null;
					oldReg = serviceReg;
					oldReg2 = recoveryReg;
					oldProvider = provider;
					serviceReg = null;
					recoveryReg = null;
					provider = null;
				}
			}
			safeUnregister(oldReg);
			safeUnregister(oldReg2);
			safeClose(oldProvider);

			if (dsfLeft) {
				DataSourceFactory newDSF = dsfTracker.getService();
				if (newDSF != null) {
					updateService(dsfTracker.getService());
				}
			}
		}
	}
}
