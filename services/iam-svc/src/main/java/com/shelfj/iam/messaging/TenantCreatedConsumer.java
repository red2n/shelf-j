package com.shelfj.iam.messaging;

import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.shelfj.iam.repo.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Consumes {@code shelfj.tenant.tenant-created} and stamps {@code tenant_id} + OWNER role on the owner user,
 * idempotently (dedupe on eventId). This closes the onboarding loop: tenant-svc creates the tenant → this stamps
 * the creator as its OWNER (visible on their next JWT refresh).
 *
 * <p>Resilient: if Kafka is down it simply doesn't consume; nothing breaks. Disable via
 * {@code shelfj.kafka.enabled=false}.</p>
 */
@ApplicationScoped
public class TenantCreatedConsumer {

    private static final Logger LOG = System.getLogger(TenantCreatedConsumer.class.getName());
    private static final String TOPIC = "shelfj.tenant.tenant-created";
    private static final String CONSUMER_NAME = "iam-svc/tenant-created";

    @Inject UserRepository users;

    @Inject @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
    boolean kafkaEnabled;
    @Inject @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
    String bootstrap;

    private KafkaConsumer<String, String> consumer;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    // Eager startup (CDI instantiates @ApplicationScoped lazily; this observer makes it eager).
    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        // no-op: @PostConstruct does the work
    }

    @PostConstruct
    void start() {
        if (!kafkaEnabled) {
            LOG.log(Level.INFO, "TenantCreated consumer disabled");
            return;
        }
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "iam-svc");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        try {
            this.consumer = new KafkaConsumer<>(props);
            this.consumer.subscribe(List.of(TOPIC));
            this.running = true;
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "iam-tenant-created-consumer");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::pollQuietly, 2, 2, TimeUnit.SECONDS);
            LOG.log(Level.INFO, "TenantCreated consumer started (bootstrap={0})", bootstrap);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "TenantCreated consumer failed to start: " + e.getMessage());
        }
    }

    private void pollQuietly() {
        if (!running) {
            return;
        }
        try {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> rec : records) {
                handle(rec.value());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "TenantCreated poll deferred: " + e.getMessage());
        }
    }

    private void handle(String json) {
        try (var reader = Json.createReader(new StringReader(json))) {
            JsonObject obj = reader.readObject();
            UUID eventId = UUID.fromString(obj.getString("eventId"));
            UUID tenantId = UUID.fromString(obj.getString("tenantId"));
            UUID ownerUserId = UUID.fromString(obj.getString("ownerUserId"));

            if (!users.markProcessedIfNew(eventId, CONSUMER_NAME)) {
                return; // already handled — idempotent
            }
            boolean changed = users.bindOwner(ownerUserId, tenantId, "OWNER");
            users.audit(tenantId, ownerUserId, "OWNER_BOUND", "via TenantCreated");
            LOG.log(Level.INFO, "Bound user {0} as OWNER of tenant {1} (changed={2})", ownerUserId, tenantId, changed);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to handle TenantCreated: " + e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (consumer != null) {
            try {
                consumer.close(Duration.ofSeconds(2));
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}
