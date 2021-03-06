/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.mqtt.generic.internal.convention.homeassistant;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.io.transport.mqtt.MqttBrokerConnection;
import org.eclipse.smarthome.io.transport.mqtt.MqttMessageSubscriber;
import org.openhab.binding.mqtt.generic.internal.generic.ChannelStateUpdateListener;
import org.openhab.binding.mqtt.generic.internal.generic.TransformationServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * Responsible for subscribing to the HomeAssistant MQTT components wildcard topic, either
 * in a time limited discovery mode or as a background discovery.
 *
 * @author David Graeff - Initial contribution
 */
@NonNullByDefault
public class DiscoverComponents implements MqttMessageSubscriber {
    private final Logger logger = LoggerFactory.getLogger(DiscoverComponents.class);
    private final ThingUID thingUID;
    private final ScheduledExecutorService scheduler;
    private final @Nullable ChannelStateUpdateListener updateListener;
    private final TransformationServiceProvider transformationServiceProvider;

    protected final CompletableFuture<@Nullable Void> discoverFinishedFuture = new CompletableFuture<>();
    private final Gson gson;

    private @Nullable ScheduledFuture<?> stopDiscoveryFuture;
    private WeakReference<@Nullable MqttBrokerConnection> connectionRef = new WeakReference<>(null);
    protected @NonNullByDefault({}) ComponentDiscovered discoveredListener;
    private int discoverTime;
    private String topic = "";

    /**
     * Implement this to get notified of new components
     */
    public static interface ComponentDiscovered {
        void componentDiscovered(HaID homeAssistantTopicID, AbstractComponent<?> component);
    }

    /**
     * Create a new discovery object.
     *
     * @param thingUID The Thing UID to perform the discovery for.
     * @param scheduler A scheduler for timeouts
     * @param channelStateUpdateListener Channel update listener. Usually the handler.
     */
    public DiscoverComponents(ThingUID thingUID, ScheduledExecutorService scheduler,
            @Nullable ChannelStateUpdateListener channelStateUpdateListener, Gson gson,
            TransformationServiceProvider transformationServiceProvider) {
        this.thingUID = thingUID;
        this.scheduler = scheduler;
        this.updateListener = channelStateUpdateListener;
        this.gson = gson;
        this.transformationServiceProvider = transformationServiceProvider;
    }

    @Override
    public void processMessage(String topic, byte[] payload) {
        if (!topic.endsWith("/config")) {
            return;
        }
        HaID haID = new HaID(topic);
        String config = new String(payload);
        AbstractComponent<?> component = CFactory.createComponent(thingUID, haID, config, updateListener, gson,
                transformationServiceProvider);
        if (component != null) {
            logger.trace("Found HomeAssistant thing {} component {}", haID.objectID, haID.component);
            if (discoveredListener != null) {
                discoveredListener.componentDiscovered(haID, component);
            }
        } else {
            logger.debug("Configuration of HomeAssistant thing {} invalid: {}", haID.objectID, config);
        }
    }

    /**
     * Start a components discovery.
     *
     * <p>
     * We need to consider the case that the remote client is using node IDs
     * and also the case that no node IDs are used.
     * </p>
     *
     * @param connection A MQTT broker connection
     * @param discoverTime The time in milliseconds for the discovery to run. Can be 0 to disable the
     *            timeout.
     *            You need to call {@link #stopDiscovery(MqttBrokerConnection)} at some
     *            point in that case.
     * @param topicDescription Contains the object-id (=device id) and potentially a node-id as well.
     * @param componentsDiscoveredListener Listener for results
     * @return A future that completes normally after the given time in milliseconds or exceptionally on any error.
     *         Completes immediately if the timeout is disabled.
     */
    public CompletableFuture<@Nullable Void> startDiscovery(MqttBrokerConnection connection, int discoverTime,
            HaID topicDescription, ComponentDiscovered componentsDiscoveredListener) {

        this.topic = topicDescription.getTopic("config");
        this.discoverTime = discoverTime;
        this.discoveredListener = componentsDiscoveredListener;
        this.connectionRef = new WeakReference<>(connection);

        // Subscribe to the wildcard topic and start receive MQTT retained topics
        connection.subscribe(topic, this).thenRun(this::subscribeSuccess).exceptionally(this::subscribeFail);

        return discoverFinishedFuture;
    }

    private void subscribeSuccess() {
        final MqttBrokerConnection connection = connectionRef.get();
        // Set up a scheduled future that will stop the discovery after the given time
        if (connection != null && discoverTime > 0) {
            this.stopDiscoveryFuture = scheduler.schedule(() -> {
                this.stopDiscoveryFuture = null;
                connection.unsubscribe(topic, this);
                this.discoveredListener = null;
                discoverFinishedFuture.complete(null);
            }, discoverTime, TimeUnit.MILLISECONDS);
        } else {
            // No timeout -> complete immediately
            discoverFinishedFuture.complete(null);
        }
    }

    private @Nullable Void subscribeFail(Throwable e) {
        final ScheduledFuture<?> scheduledFuture = this.stopDiscoveryFuture;
        if (scheduledFuture != null) { // Cancel timeout
            scheduledFuture.cancel(false);
            this.stopDiscoveryFuture = null;
        }
        this.discoveredListener = null;
        final MqttBrokerConnection connection = connectionRef.get();
        if (connection != null) {
            connection.unsubscribe(topic, this);
            connectionRef.clear();
        }
        discoverFinishedFuture.completeExceptionally(e);
        return null;
    }

    /**
     * Stops an ongoing discovery or do nothing if no discovery is running.
     *
     * @param connection A MQTT broker connection
     */
    public void stopDiscovery() {
        subscribeFail(new Throwable("Stopped"));
    }
}
