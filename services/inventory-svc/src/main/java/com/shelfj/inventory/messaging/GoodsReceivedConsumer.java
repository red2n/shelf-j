package com.shelfj.inventory.messaging;

import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.shelfj.inventory.service.InventoryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Consumes {@code shelfj.purchase.goods-received} → creates inventory batches (→ StockReceived), idempotently
 * (dedupe on eventId). This is how stock enters the system from procurement (README §9.4).
 *
 * <p>Expected payload:
 * {@code {eventId, tenantId, storeId, refId, lines:[{variantId, qty, batchNo?, costPrice?, expiryDate?}]}}.
 * Resilient: if Kafka is down it just doesn't consume.</p>
 */
@ApplicationScoped
public class GoodsReceivedConsumer {

    private static final Logger LOG = System.getLogger(GoodsReceivedConsumer.class.getName());
    private static final String TOPIC = "shelfj.purchase.goods-received";
    private static final String CONSUMER_NAME = "inventory-svc/goods-received";

    @Inject InventoryService service;
    @Inject com.shelfj.inventory.repo.InventoryRepository repo;

    @Inject @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
    boolean kafkaEnabled;
    @Inject @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
    String bootstrap;

    private KafkaConsumer<String, String> consumer;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) { /* eager */ }

    @PostConstruct
    void start() {
        if (!kafkaEnabled) {
            LOG.log(Level.INFO, "GoodsReceived consumer disabled");
            return;
        }
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "inventory-svc");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        try {
            this.consumer = new KafkaConsumer<>(props);
            this.consumer.subscribe(List.of(TOPIC));
            this.running = true;
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "inventory-goods-received-consumer");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::pollQuietly, 2, 2, TimeUnit.SECONDS);
            LOG.log(Level.INFO, "GoodsReceived consumer started (bootstrap={0})", bootstrap);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "GoodsReceived consumer failed to start: " + e.getMessage());
        }
    }

    private void pollQuietly() {
        if (!running) return;
        try {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> rec : records) {
                handle(rec.value());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "GoodsReceived poll deferred: " + e.getMessage());
        }
    }

    private void handle(String json) {
        try (var reader = Json.createReader(new StringReader(json))) {
            JsonObject obj = reader.readObject();
            UUID eventId = UUID.fromString(obj.getString("eventId"));
            if (!repo.markProcessedIfNew(eventId, CONSUMER_NAME)) {
                return; // idempotent: already handled
            }
            UUID tenantId = UUID.fromString(obj.getString("tenantId"));
            UUID storeId = UUID.fromString(obj.getString("storeId"));
            UUID refId = obj.containsKey("refId") && !obj.isNull("refId") ? UUID.fromString(obj.getString("refId")) : null;
            JsonArray lines = obj.getJsonArray("lines");
            for (int i = 0; i < lines.size(); i++) {
                JsonObject line = lines.getJsonObject(i);
                UUID variantId = UUID.fromString(line.getString("variantId"));
                BigDecimal qty = new BigDecimal(line.get("qty").toString());
                String batchNo = line.containsKey("batchNo") && !line.isNull("batchNo") ? line.getString("batchNo") : null;
                BigDecimal cost = line.containsKey("costPrice") && !line.isNull("costPrice")
                        ? new BigDecimal(line.get("costPrice").toString()) : null;
                LocalDate expiry = line.containsKey("expiryDate") && !line.isNull("expiryDate")
                        ? LocalDate.parse(line.getString("expiryDate")) : null;
                service.receive(tenantId, storeId, variantId, qty, batchNo, cost, expiry, "GRN", refId);
            }
            LOG.log(Level.INFO, "GoodsReceived {0}: created {1} batch(es)", eventId, lines.size());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to handle GoodsReceived: " + e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
        if (consumer != null) {
            try { consumer.close(Duration.ofSeconds(2)); } catch (Exception ignored) { }
        }
    }
}
