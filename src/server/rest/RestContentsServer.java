package server.rest;

import client.ContentClient;
import impl.kafka.kafka.KafkaPublisher;
import impl.kafka.kafka.KafkaSubscriber;
import impl.kafka.kafka.RecordProcessor;
import network.ServiceAnnouncer;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

public class RestContentsServer {

    private static final Logger log = Logger.getLogger(RestContentsServer.class.getName());
    private static final String KAFKA_TOPIC = "content-updates";
    private static long currentVersion = 0;

    static {
        System.setProperty("java.net.preferIPv4Stack", "true");
    }

    public static final int PORT = 8080;

    public static void main(String[] args) {
        launchServer(PORT);
        startKafkaSubscriber(); //// replicacao
    }

    public static void launchServer(int port) {
        launchServer(port, Optional.empty());
    }

    public static void launchServer(int port, long period) {
        launchServer(port, Optional.of(period));
    }

    private static void launchServer(int port, Optional<Long> period) {
        try {
            var serverURI = RestServerUtils.computeServerUri(port);
            announceService(period, serverURI);
            RestServerUtils.launchResource(serverURI, ContentResource.class);
            log.info(String.format("%s Server ready @ %s\n",  ContentClient.SERVICE, serverURI));
        } catch( Exception e) {
            log.severe(e.getMessage());
        }
    }

    private static void announceService(Optional<Long> period, String serverURI) throws IOException {
        if (period.isPresent())
            new ServiceAnnouncer(ContentClient.SERVICE, serverURI, period.get());
        else
            new ServiceAnnouncer(ContentClient.SERVICE, serverURI);
    }

    private static void startKafkaSubscriber() {
        KafkaSubscriber subscriber = KafkaSubscriber.createSubscriber("kafka:9092", List.of(KAFKA_TOPIC));
        subscriber.start(new RecordProcessor() {
            @Override
            public void onReceive(ConsumerRecord<String, String> record) {
                log.info("Received update: " + record.value());
                currentVersion = Long.parseLong(record.value());
            }
        });
    }

    public static void publishUpdateToKafka() {
        KafkaPublisher publisher = KafkaPublisher.createPublisher("kafka:9092");
        publisher.publish(KAFKA_TOPIC, String.valueOf(++currentVersion));
        publisher.close();
    }


}
