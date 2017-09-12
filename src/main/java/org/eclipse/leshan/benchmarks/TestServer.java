/*******************************************************************************
 * Copyright (c) 2017 Sierra Wireless and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *     Sierra Wireless - initial API and implementation
 *******************************************************************************/
package org.eclipse.leshan.benchmarks;

import java.util.Collection;
import java.util.Iterator;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Logger;

import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.request.ReadRequest;
import org.eclipse.leshan.core.response.ErrorCallback;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.ResponseCallback;
import org.eclipse.leshan.server.californium.LeshanServerBuilder;
import org.eclipse.leshan.server.californium.impl.LeshanServer;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.registration.RegistrationListener;
import org.eclipse.leshan.server.registration.RegistrationUpdate;
import org.eclipse.leshan.server.security.SecurityInfo;
import org.eclipse.leshan.server.security.SecurityStore;

public class TestServer {

	static final long SecurityStoreLatency = 10; // in ms;
	
	static Logger cflog;
	static {
		Logger globalLogger = Logger.getLogger("");
		globalLogger.setLevel(java.util.logging.Level.OFF);
		for (Handler handler : globalLogger.getHandlers()) {
			handler.setLevel(java.util.logging.Level.ALL);
		}

		cflog = Logger.getLogger("org.eclipse.californium");
		cflog.setLevel(java.util.logging.Level.OFF);
		for (Handler handler : cflog.getHandlers()) {
			handler.setLevel(java.util.logging.Level.ALL);
		}
	}
	
	static LeshanServer server;
	
	static AtomicInteger nbUpdate = new AtomicInteger();
	static AtomicInteger nbDereg = new AtomicInteger();
	static AtomicInteger nbReg = new AtomicInteger();
	static AtomicBoolean count = new AtomicBoolean(false);
	static AtomicInteger nbPsk = new AtomicInteger();

	private static void startServer() {
		// Build Leshan Server
		LeshanServerBuilder builder = new LeshanServerBuilder();
		SecurityStore store = new SecurityStore() {
			
			@Override
			public SecurityInfo getByIdentity(String pskIdentity) {
				try {
					Thread.sleep(SecurityStoreLatency);
				} catch (InterruptedException e) {
				}
				if (pskIdentity.startsWith("sec")) {
					nbPsk.incrementAndGet();
					return SecurityInfo.newPreSharedKeyInfo(pskIdentity, pskIdentity, new String("key").getBytes());
				}
				else {
					return null;
				}
			}
			
			@Override
			public SecurityInfo getByEndpoint(String endpoint) {
				try {
					Thread.sleep(SecurityStoreLatency);
				} catch (InterruptedException e) {
				}
				if (endpoint.startsWith("sec"))
					return SecurityInfo.newPreSharedKeyInfo(endpoint, endpoint, new String("key").getBytes());
				else
					return null;
			}
		};
		builder.setSecurityStore(store);
		builder.setCoapConfig(LeshanServerBuilder.createDefaultNetworkConfig().set(NetworkConfig.Keys.MAX_RESOURCE_BODY_SIZE, 10240));
		server = builder.build();
		
		// Add counters
		server.getRegistrationService().addListener(new RegistrationListener() {
			@Override
			public void updated(RegistrationUpdate update, Registration updatedReg, Registration previousReg) {
				if (count.get())
					nbUpdate.incrementAndGet();
			}
			@Override
			public void unregistered(Registration reg, Collection<Observation> observations, boolean expired,
					Registration newReg) {
				if (count.get())
					nbDereg.incrementAndGet();
			}
			@Override
			public void registered(Registration reg, Registration previousReg,
					Collection<Observation> previousObsersations) {
				if (count.get())
					nbReg.incrementAndGet();
				
				server.send(reg, new ReadRequest(3), new ResponseCallback<ReadResponse>() {
					@Override
					public void onResponse(ReadResponse response) {
						//System.out.println(response);
					};
				}, new ErrorCallback() {
					@Override
					public void onError(Exception e) {
						//e.printStackTrace();
					}
				});
			}
		});
		
		// Start it
		server.start();
	}

	private static void stopServer() {
		// Count remaining registration
		int i = 0;
		for (Iterator<?> iterator = server.getRegistrationService().getAllRegistrations(); iterator.hasNext();) {
			iterator.next();
			i++;
		}
		server.destroy();
		
		System.out.println("Registration Remaining: " + i);
	}

	public static void main(String[] args) {
		count.set(true);
		System.out.println("********************************************");
		System.out.println("Start counting ... ");
		startServer();
		try (Scanner scanner = new Scanner(System.in)) {
			while (scanner.hasNext()) {
				try {
					String command = scanner.next();
					if (command.equalsIgnoreCase("start")) {
						nbReg.set(0);
						nbUpdate.set(0);
						nbDereg.set(0);
						count.set(true);
						System.out.println("********************************************");
						System.out.println("Start counting ... ");
						startServer();
					} else if (command.equalsIgnoreCase("stop")) {
						System.out.println("nb update :" + nbUpdate);
						System.out.println("nb reg :" + nbReg);
						System.out.println("nb dereg : " + nbDereg);
						System.out.println("nb psk access" + nbPsk);
						nbReg.set(0);
						nbUpdate.set(0);
						nbDereg.set(0);
						count.set(false);
						Thread.sleep(500);
						stopServer();
						System.out.println("********************************************");
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}
}
