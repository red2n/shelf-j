package com.shelfj.product.messaging;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.shelfj.product.repo.ProductRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Drains the transactional outbox to Kafka on a timer (at-least-once; consumers idempotent). Resilient: if Kafka
 * is down, rows stay pending and retry. Disable via {@code shelfj.kafka.enabled=false}.
 */
@ApplicationScoped
public class OutboxPublisher {

    private static final Logger LOG = System.getLogger(OutboxPublisher.class.getName());

    @Inject ProductRepository repo;

    @Inject @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
    boolean kafkaEnabled;
    @Inject @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
    String bootstrap;
    @Inject @ConfigProperty(name = "shelfj.outbox.poll-seconds", defaultValue = "5")
    long pollSeconds;

    private KafkaProducer<String, String> producer;
    private ScheduledExecutorService scheduler;

    // Eager startup (CDI is lazy; this observer makes the bean eager so @PostConstruct fires).
    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        // no-op
    }

    @PostConstruct
    void start() {
        if (!kafkaEnabled) {
            LOG.log(Level.INFO, "Outbox publisher disabled");
            return;
        }
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "5000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
        this.producer = new KafkaProducer<>(props);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "product-outbox-publisher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::drainQuietly, pollSeconds, pollSeconds, TimeUnit.SECONDS);
        LOG.log(Level.INFO, "Outbox publisher started (bootstrap={0})", bootstrap);
    }

    private void drainQuietly() {
        try {
            for (var row : repo.pendingOutbox(100)) {
                try {
                    producer.send(new ProducerRecord<>(row.topic(), row.id().toString(), row.payload())).get();
                    repo.markPublished(row.id());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Publish failed for outbox {0}: {1}", row.id(), e.getMessage());
                    return;
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Outbox drain deferred: " + e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (producer != null) producer.close(Duration.ofSeconds(2));
    }
}
