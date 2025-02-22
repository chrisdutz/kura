/*******************************************************************************
 * Copyright (c) 2011, 2024 Eurotech and/or its affiliates and others
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *  Eurotech
 *******************************************************************************/
package org.eclipse.kura.internal.cloudconnection.eclipseiot.mqtt.cloud;

import static java.util.Objects.nonNull;
import static org.eclipse.kura.cloud.CloudPayloadEncoding.KURA_PROTOBUF;
import static org.eclipse.kura.cloud.CloudPayloadEncoding.SIMPLE_JSON;
import static org.eclipse.kura.internal.cloudconnection.eclipseiot.mqtt.message.MessageConstants.FULL_TOPIC;
import static org.eclipse.kura.internal.cloudconnection.eclipseiot.mqtt.message.MessageConstants.PRIORITY;
import static org.eclipse.kura.internal.cloudconnection.eclipseiot.mqtt.message.MessageConstants.QOS;
import static org.eclipse.kura.internal.cloudconnection.eclipseiot.mqtt.message.MessageConstants.RETAIN;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.kura.KuraConnectException;
import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.KuraInvalidMessageException;
import org.eclipse.kura.certificate.CertificatesService;
import org.eclipse.kura.cloud.CloudConnectionEstablishedEvent;
import org.eclipse.kura.cloud.CloudConnectionLostEvent;
import org.eclipse.kura.cloud.CloudPayloadEncoding;
import org.eclipse.kura.cloud.CloudPayloadProtoBufDecoder;
import org.eclipse.kura.cloud.CloudPayloadProtoBufEncoder;
import org.eclipse.kura.cloudconnection.CloudConnectionManager;
import org.eclipse.kura.cloudconnection.CloudEndpoint;
import org.eclipse.kura.cloudconnection.listener.CloudConnectionListener;
import org.eclipse.kura.cloudconnection.listener.CloudDeliveryListener;
import org.eclipse.kura.cloudconnection.message.KuraMessage;
import org.eclipse.kura.cloudconnection.publisher.CloudNotificationPublisher;
import org.eclipse.kura.cloudconnection.request.RequestHandler;
import org.eclipse.kura.cloudconnection.request.RequestHandlerRegistry;
import org.eclipse.kura.cloudconnection.subscriber.listener.CloudSubscriberListener;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.configuration.ConfigurationService;
import org.eclipse.kura.core.data.DataServiceImpl;
import org.eclipse.kura.data.DataService;
import org.eclipse.kura.data.listener.DataServiceListener;
import org.eclipse.kura.internal.cloudconnection.eclipseiot.mqtt.message.MessageType;
import org.eclipse.kura.marshalling.Marshaller;
import org.eclipse.kura.marshalling.Unmarshaller;
import org.eclipse.kura.message.KuraApplicationTopic;
import org.eclipse.kura.message.KuraPayload;
import org.eclipse.kura.net.NetworkService;
import org.eclipse.kura.net.status.NetworkInterfaceStatus;
import org.eclipse.kura.net.status.NetworkInterfaceType;
import org.eclipse.kura.net.status.NetworkStatusService;
import org.eclipse.kura.net.status.modem.ModemInterfaceStatus;
import org.eclipse.kura.net.status.modem.Sim;
import org.eclipse.kura.position.PositionLockedEvent;
import org.eclipse.kura.position.PositionService;
import org.eclipse.kura.system.SystemAdminService;
import org.eclipse.kura.system.SystemService;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudConnectionManagerImpl
        implements DataServiceListener, ConfigurableComponent, EventHandler, CloudPayloadProtoBufEncoder,
        CloudPayloadProtoBufDecoder, RequestHandlerRegistry, CloudConnectionManager, CloudEndpoint {

    private static final String KURA_PAYLOAD = "KuraPayload";

    private static final String SETUP_CLOUD_SERVICE_CONNECTION_ERROR_MESSAGE = "Cannot setup cloud service connection";

    private static final Logger logger = LoggerFactory.getLogger(CloudConnectionManagerImpl.class);

    private static final String CONNECTION_EVENT_PID_PROPERTY_KEY = "cloud.service.pid";

    static final String EVENT_TOPIC_DEPLOYMENT_ADMIN_INSTALL = "org/osgi/service/deployment/INSTALL";
    static final String EVENT_TOPIC_DEPLOYMENT_ADMIN_UNINSTALL = "org/osgi/service/deployment/UNINSTALL";

    private static final int NUM_CONCURRENT_CALLBACKS = 2;

    private static ExecutorService callbackExecutor = Executors.newFixedThreadPool(NUM_CONCURRENT_CALLBACKS);

    private ComponentContext ctx;

    private CloudConnectionManagerOptions options;

    private DataService dataService;
    private SystemService systemService;
    private SystemAdminService systemAdminService;
    private NetworkService networkService;
    private PositionService positionService;
    private EventAdmin eventAdmin;
    private CertificatesService certificatesService;
    private Unmarshaller jsonUnmarshaller;
    private Marshaller jsonMarshaller;
    private Optional<NetworkStatusService> networkStatusService = Optional.empty();

    // package visibility for LifeCyclePayloadBuilder
    String imei;
    String iccid;
    String imsi;
    String rssi;
    String modemFwVer;

    private String ownPid;

    private final AtomicInteger messageId;

    private ServiceRegistration<?> cloudServiceRegistration;

    private final Map<String, RequestHandler> registeredRequestHandlers;

    private final Set<CloudConnectionListener> registeredCloudConnectionListeners;
    private final Set<CloudPublisherDeliveryListener> registeredCloudPublisherDeliveryListeners;
    private final Set<CloudDeliveryListener> registeredCloudDeliveryListeners;

    private ScheduledFuture<?> scheduledBirthPublisherFuture;
    private ScheduledExecutorService scheduledBirthPublisher = Executors.newScheduledThreadPool(1);

    public CloudConnectionManagerImpl() {
        this.messageId = new AtomicInteger();
        this.registeredCloudConnectionListeners = new CopyOnWriteArraySet<>();
        this.registeredRequestHandlers = new HashMap<>();
        this.registeredCloudPublisherDeliveryListeners = new CopyOnWriteArraySet<>();
        this.registeredCloudDeliveryListeners = new CopyOnWriteArraySet<>();
    }

    // ----------------------------------------------------------------
    //
    // Dependencies
    //
    // ----------------------------------------------------------------

    public void setDataService(DataService dataService) {
        this.dataService = dataService;
    }

    public void unsetDataService(DataService dataService) {
        if (this.dataService.equals(dataService)) {
            this.dataService = null;
        }
    }

    public DataService getDataService() {
        return this.dataService;
    }

    public void setSystemAdminService(SystemAdminService systemAdminService) {
        this.systemAdminService = systemAdminService;
    }

    public void unsetSystemAdminService(SystemAdminService systemAdminService) {
        if (this.systemAdminService.equals(systemAdminService)) {
            this.systemAdminService = null;
        }
    }

    public SystemAdminService getSystemAdminService() {
        return this.systemAdminService;
    }

    public void setSystemService(SystemService systemService) {
        this.systemService = systemService;
    }

    public void unsetSystemService(SystemService systemService) {
        if (this.systemService.equals(systemService)) {
            this.systemService = null;
        }
    }

    public SystemService getSystemService() {
        return this.systemService;
    }

    public void setNetworkService(NetworkService networkService) {
        this.networkService = networkService;
    }

    public void unsetNetworkService(NetworkService networkService) {
        if (this.networkService.equals(networkService)) {
            this.networkService = null;
        }
    }

    public NetworkService getNetworkService() {
        return this.networkService;
    }

    public void setPositionService(PositionService positionService) {
        this.positionService = positionService;
    }

    public void unsetPositionService(PositionService positionService) {
        if (this.positionService.equals(positionService)) {
            this.positionService = null;
        }
    }

    public PositionService getPositionService() {
        return this.positionService;
    }

    public void setEventAdmin(EventAdmin eventAdmin) {
        this.eventAdmin = eventAdmin;
    }

    public void unsetEventAdmin(EventAdmin eventAdmin) {
        if (this.eventAdmin.equals(eventAdmin)) {
            this.eventAdmin = null;
        }
    }

    public void setJsonUnmarshaller(Unmarshaller jsonUnmarshaller) {
        this.jsonUnmarshaller = jsonUnmarshaller;
    }

    public void unsetJsonUnmarshaller(Unmarshaller jsonUnmarshaller) {
        if (this.jsonUnmarshaller.equals(jsonUnmarshaller)) {
            this.jsonUnmarshaller = null;
        }
    }

    public void setJsonMarshaller(Marshaller jsonMarshaller) {
        this.jsonMarshaller = jsonMarshaller;
    }

    public void unsetJsonMarshaller(Marshaller jsonMarshaller) {
        if (this.jsonMarshaller.equals(jsonMarshaller)) {
            this.jsonMarshaller = null;
        }
    }

    public void setNetworkStatusService(NetworkStatusService networkStatusService) {
        this.networkStatusService = Optional.of(networkStatusService);
    }

    public void unsetNetworkStatusService(NetworkStatusService networkStatusService) {
        if (this.networkStatusService.isPresent() && this.networkStatusService.get().equals(networkStatusService)) {
            this.networkStatusService = Optional.empty();
        }
    }

    // ----------------------------------------------------------------
    //
    // Activation APIs
    //
    // ----------------------------------------------------------------

    protected void activate(ComponentContext componentContext, Map<String, Object> properties) {
        this.ownPid = (String) properties.get(ConfigurationService.KURA_SERVICE_PID);

        logger.info("activate {}...", ownPid);

        //
        // save the bundle context and the properties
        this.ctx = componentContext;
        this.options = new CloudConnectionManagerOptions(properties, this.systemService);
        //
        // install event listener for GPS locked event
        Dictionary<String, Object> props = new Hashtable<>();
        String[] eventTopics = { PositionLockedEvent.POSITION_LOCKED_EVENT_TOPIC, EVENT_TOPIC_DEPLOYMENT_ADMIN_INSTALL,
                EVENT_TOPIC_DEPLOYMENT_ADMIN_UNINSTALL };
        props.put(EventConstants.EVENT_TOPIC, eventTopics);
        this.cloudServiceRegistration = this.ctx.getBundleContext().registerService(EventHandler.class.getName(), this,
                props);

        this.dataService.addDataServiceListener(this);

        //
        // Usually the cloud connection is setup in the
        // onConnectionEstablished callback.
        // Since the callback may be lost if we are activated
        // too late (the DataService is already connected) we
        // setup the cloud connection here.
        if (isConnected()) {
            logger.warn("DataService is already connected. Publish BIRTH certificate");
            try {
                setupCloudConnection(false);
            } catch (KuraException e) {
                logger.warn(SETUP_CLOUD_SERVICE_CONNECTION_ERROR_MESSAGE, e);
            }
        }
    }

    public void updated(Map<String, Object> properties) {
        logger.info("updated {}...: {}", properties.get(ConfigurationService.KURA_SERVICE_PID), properties);

        // Update properties and re-publish Birth certificate
        this.options = new CloudConnectionManagerOptions(properties, this.systemService);
        if (isConnected()) {
            try {
                setupCloudConnection(false);
            } catch (KuraException e) {
                logger.warn(SETUP_CLOUD_SERVICE_CONNECTION_ERROR_MESSAGE);
            }
        }
    }

    protected void deactivate(ComponentContext componentContext) {
        logger.info("deactivate {}...", componentContext.getProperties().get(ConfigurationService.KURA_SERVICE_PID));

        if (isConnected()) {
            try {
                publishDisconnectCertificate();
            } catch (KuraException e) {
                logger.warn("Cannot publish disconnect certificate");
            }
        }

        this.dataService.removeDataServiceListener(this);

        this.dataService = null;
        this.systemService = null;
        this.systemAdminService = null;
        this.networkService = null;
        this.positionService = null;
        this.eventAdmin = null;

        this.cloudServiceRegistration.unregister();
    }

    @Override
    public void handleEvent(Event event) {
        String topic = event.getTopic();

        if (PositionLockedEvent.POSITION_LOCKED_EVENT_TOPIC.contains(topic)) {
            handlePositionLockedEvent();
            return;
        }

        if ((EVENT_TOPIC_DEPLOYMENT_ADMIN_INSTALL.equals(topic) || EVENT_TOPIC_DEPLOYMENT_ADMIN_UNINSTALL.equals(topic))
                && this.dataService.isConnected()) {
            logger.debug("CloudConnectionManagerImpl: received install/uninstall event, publishing BIRTH.");
            tryPublishBirthCertificate(false);
        }
    }

    private void handlePositionLockedEvent() {
        // if we get a position locked event,
        // republish the birth certificate only if we are configured to
        logger.info("Handling PositionLockedEvent");
        if (this.dataService.isConnected() && this.options.getRepubBirthCertOnGpsLock()) {
            tryPublishBirthCertificate(false);
        }
    }

    private void tryPublishBirthCertificate(boolean isNewConnection) {
        try {
            publishBirthCertificate(isNewConnection);
        } catch (KuraException e) {
            logger.warn("Cannot publish birth certificate", e);
        }
    }

    // ----------------------------------------------------------------
    //
    // Service APIs
    //
    // ----------------------------------------------------------------

    @Override
    public boolean isConnected() {
        return this.dataService != null && this.dataService.isConnected();
    }

    // ----------------------------------------------------------------
    //
    // Package APIs
    //
    // ----------------------------------------------------------------

    public CloudConnectionManagerOptions getCloudConnectionManagerOptions() {
        return this.options;
    }

    public byte[] encodePayload(KuraPayload payload) throws KuraException {
        byte[] bytes;
        CloudPayloadEncoding preferencesEncoding = this.options.getPayloadEncoding();

        if (preferencesEncoding == KURA_PROTOBUF) {
            bytes = encodeProtobufPayload(payload);
        } else if (preferencesEncoding == SIMPLE_JSON) {
            bytes = encodeJsonPayload(payload);
        } else {
            throw new KuraException(KuraErrorCode.ENCODE_ERROR, KURA_PAYLOAD);
        }
        return bytes;
    }

    // ----------------------------------------------------------------
    //
    // DataServiceListener API
    //
    // ----------------------------------------------------------------

    @Override
    public void onConnectionEstablished() {
        try {
            setupCloudConnection(true);
        } catch (KuraException e) {
            logger.warn(SETUP_CLOUD_SERVICE_CONNECTION_ERROR_MESSAGE);
        }

        postConnectionStateChangeEvent(true);

        this.registeredCloudConnectionListeners.forEach(CloudConnectionListener::onConnectionEstablished);
    }

    @Override
    public void onDisconnecting() {
        // publish disconnect certificate
        try {
            publishDisconnectCertificate();
        } catch (KuraException e) {
            logger.warn("Cannot publish disconnect certificate");
        }
    }

    @Override
    public void onDisconnected() {
        // raise event
        postConnectionStateChangeEvent(false);

        this.registeredCloudConnectionListeners.forEach(CloudConnectionListener::onDisconnected);
    }

    @Override
    public void onConnectionLost(Throwable cause) {
        // raise event
        postConnectionStateChangeEvent(false);

        this.registeredCloudConnectionListeners.forEach(CloudConnectionListener::onConnectionLost);
    }

    @Override
    public void onMessageArrived(String topic, byte[] payload, int qos, boolean retained) {
        logger.info("Message arrived on topic: {}", topic);

        // notify listeners
        ControlTopic kuraTopic = new ControlTopic(topic, MessageType.CONTROL.getTopicPrefix());

        KuraPayload kuraPayload = null;

        if (this.options.getPayloadEncoding() == SIMPLE_JSON) {
            try {
                kuraPayload = createKuraPayloadFromJson(payload);
            } catch (KuraException e) {
                logger.warn("Error creating Kura Payload from Json", e);
            }
        } else if (this.options.getPayloadEncoding() == KURA_PROTOBUF) {
            kuraPayload = createKuraPayloadFromProtoBuf(topic, payload);
        }

        try {
            boolean validMessage = isValidMessage(kuraTopic, kuraPayload);

            if (validMessage) {
                dispatchControlMessage(kuraTopic, kuraPayload);
            } else {
                logger.warn("Message verification failed! Not valid signature or message not signed.");
            }

        } catch (Exception e) {
            logger.error("Error during CloudClientListener notification.", e);
        }

    }

    private void dispatchControlMessage(ControlTopic kuraTopic, KuraPayload kuraPayload) {

        String applicationId = kuraTopic.getApplicationId();

        kuraPayload.addMetric(MessageHandlerCallable.METRIC_REQUEST_ID, kuraTopic.getReqId());

        RequestHandler cloudlet = this.registeredRequestHandlers.get(applicationId);
        if (cloudlet != null) {

            callbackExecutor
                    .submit(new MessageHandlerCallable(cloudlet, kuraTopic.getApplicationTopic(), kuraPayload, this));
        }
    }

    private boolean isValidMessage(KuraApplicationTopic kuraAppTopic, KuraPayload kuraPayload) {
        if (this.certificatesService == null) {
            ServiceReference<CertificatesService> sr = this.ctx.getBundleContext()
                    .getServiceReference(CertificatesService.class);
            if (sr != null) {
                this.certificatesService = this.ctx.getBundleContext().getService(sr);
            }
        }
        boolean validMessage = false;
        if (this.certificatesService == null || this.certificatesService.verifySignature(kuraAppTopic, kuraPayload)) {
            validMessage = true;
        }
        return validMessage;
    }

    @Override
    public void onMessagePublished(int messageId, String topic) {
        synchronized (this.messageId) {
            if (this.messageId.get() != -1 && this.messageId.get() == messageId) {
                if (this.options.getLifeCycleMessageQos() == 0) {
                    this.messageId.set(-1);
                }
                this.messageId.notifyAll();
            }
        }
    }

    @Override
    public void onMessageConfirmed(int messageId, String topic) {
        synchronized (this.messageId) {
            if (this.messageId.get() != -1 && this.messageId.get() == messageId) {
                this.messageId.set(-1);
                this.messageId.notifyAll();
            }
        }

        this.registeredCloudPublisherDeliveryListeners
                .forEach(deliveryListener -> deliveryListener.onMessageConfirmed(String.valueOf(messageId), topic));

        this.registeredCloudDeliveryListeners
                .forEach(deliveryListener -> deliveryListener.onMessageConfirmed(String.valueOf(messageId)));
    }

    // ----------------------------------------------------------------
    //
    // CloudPayloadProtoBufEncoder API
    //
    // ----------------------------------------------------------------

    @Override
    public byte[] getBytes(KuraPayload kuraPayload, boolean gzipped) throws KuraException {
        CloudPayloadEncoder encoder = new CloudPayloadProtoBufEncoderImpl(kuraPayload);
        if (gzipped) {
            encoder = new CloudPayloadGZipEncoder(encoder);
        }

        byte[] bytes;
        try {
            bytes = encoder.getBytes();
            return bytes;
        } catch (IOException e) {
            throw new KuraException(KuraErrorCode.ENCODE_ERROR, KURA_PAYLOAD, e);
        }
    }

    // ----------------------------------------------------------------
    //
    // CloudPayloadProtoBufDecoder API
    //
    // ----------------------------------------------------------------

    @Override
    public KuraPayload buildFromByteArray(byte[] payload) throws KuraException {
        CloudPayloadProtoBufDecoderImpl encoder = new CloudPayloadProtoBufDecoderImpl(payload);
        KuraPayload kuraPayload;

        try {
            kuraPayload = encoder.buildFromByteArray();
            return kuraPayload;
        } catch (KuraInvalidMessageException | IOException e) {
            throw new KuraException(KuraErrorCode.DECODER_ERROR, KURA_PAYLOAD, e);
        }
    }

    // ----------------------------------------------------------------
    //
    // Birth and Disconnect Certificates
    //
    // ----------------------------------------------------------------

    private void setupCloudConnection(boolean isNewConnection) throws KuraException {
        publishBirthCertificate(isNewConnection);
        setupDeviceSubscriptions();
    }

    private void setupDeviceSubscriptions() throws KuraException {
        StringBuilder sbDeviceSubscription = new StringBuilder();
        sbDeviceSubscription.append(MessageType.CONTROL.getTopicPrefix()).append(this.options.getTopicSeparator())
                .append("+").append(this.options.getTopicSeparator()).append("+")
                .append(this.options.getTopicSeparator()).append("req").append(this.options.getTopicSeparator())
                .append(this.options.getTopicWildCard());

        this.dataService.subscribe(sbDeviceSubscription.toString(), 0);
    }

    private void publishBirthCertificate(boolean isNewConnection) throws KuraException {
        if (isFrameworkStopping()) {
            logger.info("framework is stopping.. not republishing birth certificate");
            return;
        }
        
        readModemProfile();
        LifecycleMessage birthToPublish = new LifecycleMessage(this.options, this).asBirthCertificateMessage();

        if (isNewConnection) {
            publishLifeCycleMessage(birthToPublish);
        } else {
            publishWithDelay(birthToPublish);
        }
    }

    private void publishDisconnectCertificate() throws KuraException {
        publishLifeCycleMessage(new LifecycleMessage(this.options, this).asDisconnectCertificateMessage());
    }

    private void publishWithDelay(LifecycleMessage message) {
        if (Objects.nonNull(this.scheduledBirthPublisherFuture)) {
            this.scheduledBirthPublisherFuture.cancel(false);
            logger.debug("CloudConnectionManagerImpl: BIRTH message cache timer restarted.");
        }

        logger.debug("CloudConnectionManagerImpl: BIRTH message cached for 30s.");

        this.scheduledBirthPublisherFuture = this.scheduledBirthPublisher.schedule(() -> {
            try {
                logger.debug("CloudConnectionManagerImpl: publishing cached BIRTH message.");
                publishLifeCycleMessage(message);
            } catch (KuraException e) {
                logger.error("Error sending cached BIRTH/APP certificate.", e);
            }
        }, 30L, TimeUnit.SECONDS);
    }

    private void publishLifeCycleMessage(LifecycleMessage message) throws KuraException {
        // track the message ID and block until the message
        // has been published (i.e. written to the socket).
        synchronized (this.messageId) {
            this.messageId.set(-1);
            // add a timestamp to the message
            KuraPayload payload = message.getPayload();
            payload.setTimestamp(new Date());
            byte[] encodedPayload = encodePayload(payload);
            int localMessageId = this.dataService.publish(message.getTopic(), encodedPayload,
                    this.options.getLifeCycleMessageQos(), this.options.getLifeCycleMessageRetain(),
                    this.options.getLifeCycleMessagePriority());
            this.messageId.set(localMessageId);
            try {
                this.messageId.wait(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("Interrupted while waiting for the message to be published", e);
            }
        }
    }

    private byte[] encodeProtobufPayload(KuraPayload payload) throws KuraException {
        byte[] bytes = new byte[0];
        if (payload == null) {
            return bytes;
        }

        CloudPayloadEncoder encoder = new CloudPayloadProtoBufEncoderImpl(payload);
        if (this.options.getEncodeGzip()) {
            encoder = new CloudPayloadGZipEncoder(encoder);
        }

        try {
            bytes = encoder.getBytes();
        } catch (IOException e) {
            throw new KuraException(KuraErrorCode.ENCODE_ERROR, KURA_PAYLOAD, e);
        }
        return bytes;
    }

    private byte[] encodeJsonPayload(KuraPayload payload) throws KuraException {
        return this.jsonMarshaller.marshal(payload).getBytes(StandardCharsets.UTF_8);
    }

    private KuraPayload createKuraPayloadFromJson(byte[] payload) throws KuraException {
        return this.jsonUnmarshaller.unmarshal(new String(payload), KuraPayload.class);
    }

    private KuraPayload createKuraPayloadFromProtoBuf(String topic, byte[] payload) {
        KuraPayload kuraPayload;
        try {
            // try to decode the message into an KuraPayload
            kuraPayload = new CloudPayloadProtoBufDecoderImpl(payload).buildFromByteArray();
        } catch (Exception e) {
            // Wrap the received bytes payload into an KuraPayload
            logger.debug("Received message on topic {} that could not be decoded. Wrapping it into an KuraPayload.",
                    topic);
            kuraPayload = new KuraPayload();
            kuraPayload.setBody(payload);
        }
        return kuraPayload;
    }

    private void postConnectionStateChangeEvent(final boolean isConnected) {

        final Map<String, Object> eventProperties = Collections.singletonMap(CONNECTION_EVENT_PID_PROPERTY_KEY,
                (String) this.ctx.getProperties().get(ConfigurationService.KURA_SERVICE_PID));

        final Event event = isConnected ? new CloudConnectionEstablishedEvent(eventProperties)
                : new CloudConnectionLostEvent(eventProperties);
        this.eventAdmin.postEvent(event);
    }

    @Override
    public void connect() throws KuraConnectException {
        if (this.dataService != null) {
            this.dataService.connect();
        }
    }

    @Override
    public void disconnect() {
        if (this.dataService != null) {
            this.dataService.disconnect(10);
        }
    }

    @Override
    public Map<String, String> getInfo() {
        DataServiceImpl dataServiceImpl = (DataServiceImpl) this.dataService;
        return dataServiceImpl.getConnectionInfo();
    }

    @Override
    public void registerCloudConnectionListener(CloudConnectionListener cloudConnectionListener) {
        this.registeredCloudConnectionListeners.add(cloudConnectionListener);
    }

    @Override
    public void unregisterCloudConnectionListener(CloudConnectionListener cloudConnectionListener) {
        this.registeredCloudConnectionListeners.remove(cloudConnectionListener);
    }

    public void registerCloudPublisherDeliveryListener(CloudPublisherDeliveryListener cloudPublisherDeliveryListener) {
        this.registeredCloudPublisherDeliveryListeners.add(cloudPublisherDeliveryListener);
    }

    public void unregisterCloudPublisherDeliveryListener(
            CloudPublisherDeliveryListener cloudPublisherDeliveryListener) {
        this.registeredCloudPublisherDeliveryListeners.remove(cloudPublisherDeliveryListener);
    }

    @Override
    public String publish(KuraMessage message) throws KuraException {
        Map<String, Object> messageProps = message.getProperties();
        String fullTopic = (String) messageProps.get(FULL_TOPIC.name());
        int qos = (Integer) messageProps.get(QOS.name());
        boolean retain = (Boolean) messageProps.get(RETAIN.name());
        int priority = (Integer) messageProps.get(PRIORITY.name());

        byte[] appPayload = encodePayload(message.getPayload());

        int id = this.dataService.publish(fullTopic, appPayload, qos, retain, priority);

        if (qos == 0) {
            return null;
        }
        return String.valueOf(id);
    }

    String getOwnPid() {
        return ownPid;
    }

    @Override
    public void registerSubscriber(Map<String, Object> subscriptionProperties, CloudSubscriberListener subscriber) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unregisterSubscriber(CloudSubscriberListener subscriberListener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerRequestHandler(String id, RequestHandler requestHandler) throws KuraException {
        this.registeredRequestHandlers.put(id, requestHandler);
    }

    @Override
    public void unregister(String id) throws KuraException {
        this.registeredRequestHandlers.remove(id);
    }

    public String getNotificationPublisherPid() {
        throw new UnsupportedOperationException();
    }

    public CloudNotificationPublisher getNotificationPublisher() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerCloudDeliveryListener(CloudDeliveryListener cloudDeliveryListener) {
        this.registeredCloudDeliveryListeners.add(cloudDeliveryListener);

    }

    @Override
    public void unregisterCloudDeliveryListener(CloudDeliveryListener cloudDeliveryListener) {
        this.registeredCloudDeliveryListeners.remove(cloudDeliveryListener);
    }
    
    private boolean isFrameworkStopping() {
        try {
            final Bundle ownBundle = FrameworkUtil.getBundle(CloudConnectionManagerImpl.class);

            if (ownBundle == null) {
                return false; // not running in an OSGi framework? e.g. unit test
            }

            return ownBundle.getBundleContext().getBundle(0).getState() == Bundle.STOPPING;
        } catch (final Exception e) {
            logger.warn("unexpected exception while checking if framework is shutting down", e);
            return false;
        }
    }

    private void readModemProfile() {
        this.networkStatusService.ifPresent(statusService -> {
            List<ModemInterfaceStatus> modemStatuses = getModemsStatuses(statusService);
            if (nonNull(modemStatuses) && !modemStatuses.isEmpty()) {
                readModemInfos(modemStatuses);
            } else {
                this.imei = null;
                this.iccid = null;
                this.imsi = null;
                this.rssi = null;
                this.modemFwVer = null;
            }
        });
    }

    private List<ModemInterfaceStatus> getModemsStatuses(NetworkStatusService networkStatusService) {
        List<ModemInterfaceStatus> modemStatuses = new ArrayList<>();
        try {
            List<String> interfaceIds = networkStatusService.getInterfaceIds();
            for (String interfaceId : interfaceIds) {
                Optional<NetworkInterfaceStatus> networkInterfaceStatus = networkStatusService
                        .getNetworkStatus(interfaceId);
                networkInterfaceStatus.ifPresent(state -> {
                    NetworkInterfaceType type = state.getType();
                    if (NetworkInterfaceType.MODEM.equals(type)) {
                        modemStatuses.add((ModemInterfaceStatus) state);
                    }
                });
            }
        } catch (KuraException e) {
            logger.error("Error reading modem profile", e);
        }
        return modemStatuses;
    }

    private void readModemInfos(List<ModemInterfaceStatus> modemStatuses) {
        Collections.sort(modemStatuses, Comparator.comparing(ModemInterfaceStatus::getConnectionStatus));
        ModemInterfaceStatus modemStatus = modemStatuses.get(modemStatuses.size() - 1);
        Optional<Sim> activeSim = Optional.empty();

        List<Sim> availableSims = modemStatus.getAvailableSims();
        for (Sim sim : availableSims) {
            if (sim.isActive() && sim.isPrimary()) {
                activeSim = Optional.of(sim);
            }
        }

        this.iccid = "NA";
        this.imsi = "NA";
        activeSim.ifPresent(sim -> {
            this.iccid = sim.getIccid();
            this.imsi = sim.getImsi();
        });
        this.imei = modemStatus.getSerialNumber();
        this.rssi = String.valueOf(modemStatus.getSignalStrength());
        this.modemFwVer = modemStatus.getFirmwareVersion();

    }
}
