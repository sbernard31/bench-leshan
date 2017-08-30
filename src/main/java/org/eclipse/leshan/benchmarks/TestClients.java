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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.eclipse.leshan.LwM2mId;
import org.eclipse.leshan.ResponseCode;
import org.eclipse.leshan.client.californium.LeshanClient;
import org.eclipse.leshan.client.californium.LeshanClientBuilder;
import org.eclipse.leshan.client.object.Device;
import org.eclipse.leshan.client.object.Security;
import org.eclipse.leshan.client.object.Server;
import org.eclipse.leshan.client.observer.LwM2mClientObserverAdapter;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.client.servers.DmServerInfo;
import org.eclipse.leshan.core.request.BindingMode;
import org.eclipse.leshan.core.response.ExecuteResponse;

public class TestClients {

	// Number of client to start
	public static final int NBCLIENT = 1000;
	// Time the client remains registered in ms
	public static final long timeAlive = 100;
	// True if you want to use DTLS
	public static final boolean secure = true;
	

	private static CountDownLatch latch;
	private static ScheduledExecutorService executor = Executors
			.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());

	private static final AtomicInteger nb_success = new AtomicInteger();
	private static final AtomicInteger nb_timeout = new AtomicInteger();
	private static final AtomicInteger nb_failure = new AtomicInteger();
	private static final AtomicInteger nb_internal = new AtomicInteger();

	private static CopyOnWriteArrayList<Long> success_times = new CopyOnWriteArrayList<>(); // in
																							// ms
	private static CopyOnWriteArrayList<Long> timeout_times = new CopyOnWriteArrayList<>(); // in
																							// ms
	private static CopyOnWriteArrayList<Long> failure_times = new CopyOnWriteArrayList<>(); // in
																							// ms
	private static CopyOnWriteArrayList<Long> internal_times = new CopyOnWriteArrayList<>(); // in
																								// ms

	static {
		// disable java.util.logging
		LogManager.getLogManager().reset();
		Logger globalLogger = Logger.getGlobal();
		globalLogger.setLevel(java.util.logging.Level.OFF);
		Handler[] handlers = globalLogger.getHandlers();
		for (Handler handler : handlers) {
			globalLogger.removeHandler(handler);
		}
	}

	public static LeshanClient createClient(final int i) {
		// generate random identity
		String endpoint = secure ? "secdevice" + i :  "device" + i ;

		// Create objects Enabler
		ObjectsInitializer initializer = new ObjectsInitializer();
		if (secure)
			initializer.setInstancesForObject(LwM2mId.SECURITY,
					Security.psk("coaps://localhost:5684", 12345, endpoint.getBytes(), new String("key").getBytes()));
		else
			initializer.setInstancesForObject(LwM2mId.SECURITY,
					Security.noSec("coap://localhost:5683",12345));
		initializer.setInstancesForObject(LwM2mId.SERVER, new Server(12345, 36000, BindingMode.U, false));
		initializer.setInstancesForObject(LwM2mId.DEVICE,
				new Device("Eclipse Leshan", "IT - TEST - 123", "12345", "U") {

					@Override
					public ExecuteResponse execute(int resourceid, String params) {
						if (resourceid == 4) {
							return ExecuteResponse.success();
						} else {
							return super.execute(resourceid, params);
						}
					}
				});
		List<LwM2mObjectEnabler> objects = initializer.createMandatory();
		objects.add(initializer.create(2));

		// Build Client
		LeshanClientBuilder builder = new LeshanClientBuilder(endpoint);
		builder.setObjects(objects);
		if (secure)
			builder.noUnsecureEndpoint();
		else
			builder.noSecureEndpoint();

		return builder.build();
	}

	public static void startClient(final LeshanClient client) {

		// measurement stuff
		final long start = System.currentTimeMillis();
		client.addObserver(new LwM2mClientObserverAdapter() {

			@Override
			public void onRegistrationSuccess(DmServerInfo server, String registrationID) {
				long end = System.currentTimeMillis();
				nb_success.incrementAndGet();
				success_times.add(end - start);

				executor.schedule(new Runnable() {

					@Override
					public void run() {
						client.stop(true);
					}
				}, timeAlive, TimeUnit.MILLISECONDS);

			}

			@Override
			public void onRegistrationFailure(DmServerInfo server, ResponseCode responseCode, String errorMessage) {
				long end = System.currentTimeMillis();
				nb_failure.incrementAndGet();
				failure_times.add(end - start);
				latch.countDown();
				System.out.println(errorMessage);
			}

			@Override
			public void onRegistrationTimeout(DmServerInfo server) {
				long end = System.currentTimeMillis();
				nb_timeout.incrementAndGet();
				timeout_times.add(end - start);
				latch.countDown();
			}

			@Override
			public void onDeregistrationSuccess(DmServerInfo server, String registrationID) {
				latch.countDown();
			}

			@Override
			public void onDeregistrationFailure(DmServerInfo server, ResponseCode responseCode, String errorMessage) {
				latch.countDown();
			}

			@Override
			public void onDeregistrationTimeout(DmServerInfo server) {
				latch.countDown();
			}
		});
		client.start();
	}

	public static void main(String[] args) {
		latch = new CountDownLatch(NBCLIENT);

		// Create all clients
		List<LeshanClient> clients = new ArrayList<>(NBCLIENT);
		for (int i = 0; i < NBCLIENT; i++) {
			clients.add(createClient(i));
		}

		// Start it
		for (LeshanClient client : clients) {
			startClient(client);
		}

		// wait & display results
		try {
			boolean terminated = latch.await(15, TimeUnit.SECONDS);
			System.out.println("********************************************");
			System.out.println(terminated ? "Terminated !" : "Timeout ...");
			printResult("success", nb_success.get(), success_times);
			printResult("failure", nb_failure.get(), failure_times);
			printResult("timeout", nb_timeout.get(), timeout_times);
			printResult("success", nb_internal.get(), internal_times);
			System.out.println("********************************************");
			for (LeshanClient client : clients) {
				client.destroy(false);
			}
			executor.shutdownNow();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private static void printResult(String name, int nb, Collection<Long> times) {
		System.out.print(String.format("nb %s        : %d", name, nb));
		if (!times.isEmpty())
			System.out.print(String.format(" (max %dms, min %dms, avg %dms)", Collections.max(times),
					Collections.min(times), average(times)));
		System.out.println();
	}

	private static long average(Collection<Long> c) {
		long sum = 0;
		for (Long s : c) {
			sum += s;
		}
		return sum / c.size();
	}
}
