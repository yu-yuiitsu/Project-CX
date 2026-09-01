package org.yu.projectcx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.PayloadParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles polymorphic dispatching, validation, and serialization of any NetworkPayload subtype.
 * 
 * Demonstrates:
 * - Polymorphism (Dynamic Dispatch): Interacts with any payload via the abstract NetworkPayload
 *   reference, invoking overridden serialize(), validate(), and getSummary() at runtime.
 */
public class PayloadDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(PayloadDispatcher.class);

    public interface PayloadListener {
        void onPayloadDispatched(NetworkPayload payload, String serializedData);
    }

    private final List<PayloadListener> listeners = new ArrayList<>();

    public void addListener(PayloadListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Polymorphically processes and dispatches any NetworkPayload subtype.
     */
    public String dispatch(NetworkPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Cannot dispatch a null payload.");
        }

        // Polymorphic validation call
        payload.validate();

        // Polymorphic serialization call (invokes TextMessage or FileTransfer implementation)
        String wireData = payload.serialize();

        // Polymorphic summary call
        logger.info("Dispatching payload [{}]: {}", payload.getType(), payload.getSummary());
        logger.debug("Wire data: {}", wireData);

        for (PayloadListener listener : listeners) {
            listener.onPayloadDispatched(payload, wireData);
        }

        return wireData;
    }

    /**
     * Ingests raw wire data, deserializes it polymorphically, and returns the concrete NetworkPayload.
     */
    public NetworkPayload ingest(String rawWireData) {
        NetworkPayload payload = PayloadParser.parse(rawWireData);
        payload.validate();
        logger.info("Ingested payload polymorphically: {}", payload.getSummary());
        return payload;
    }
}
