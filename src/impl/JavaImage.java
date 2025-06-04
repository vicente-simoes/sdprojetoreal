package impl;

import api.java.Content;
import api.java.Image;
import api.java.Result;
import api.java.Users;
import client.ContentClient;
import client.UsersClient;
import impl.kafka.kafka.KafkaPublisher;
import impl.kafka.kafka.KafkaSubscriber;
import impl.kafka.kafka.KafkaUtils;
import impl.kafka.kafka.RecordProcessor;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.core.UriBuilder;
import network.DataModelAdaptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

import static api.java.Result.ErrorCode.*;
import static api.java.Result.*;

public class JavaImage implements Image, RecordProcessor {

    private static final Logger log = Logger.getLogger(JavaImage.class.getName());

    private static final String IMAGES_DIR = "./media";

    private Users users;

    private Content content;

    private KafkaPublisher publisher;

    private KafkaSubscriber subscriber;

    private static final long DELETION_INTERVAL_SECONDS = 15;
    private static final long DELETION_GRACE_PERIOD_MILLIS = 30 * 1000; // 30 segundos
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final ConcurrentMap<String, ImageCounterInfo> imageReferenceCounts = new ConcurrentHashMap<>();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public JavaImage() {
        if (content == null)
            log.info("Content service not set, using default implementation.");
        if (users == null)
            log.info("Users service not set, using default implementation.");
        startImageDeletionPublisher();
        startImageReferenceListener();
        startDeletionScheduler();
        try {
            Files.createDirectories(Paths.get(IMAGES_DIR));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void setUsers(Users users) {
        this.users = users;
    }

    public void setContent(Content content) {
        this.content = content;
    }


    @Override
    public Result<String> createImage(String uid, byte[] content, String pwd) {
        log.info("createImage(uid -> %s, content _, pwd -> %s)\n".formatted(uid, pwd));
        if (content == null || content.length == 0)
            return error(BAD_REQUEST);
        var uRes = users.getUser(uid, pwd);
        if (!uRes.isOK())
            return error(uRes.error());
        var iid = UUID.randomUUID().toString();
        var path = Paths.get(IMAGES_DIR, uid, iid);
        createPathDirectories(uid);
        storeImage(path, content);

        // --- NOVA LÓGICA AQUI ---
        String mediaUrl = UriBuilder.fromUri(uid).path(iid).build().toASCIIString(); // Reconstroi a mediaUrl como o Content a usaria
        String keyName = String.format("%s/%s", uid, iid); // A chave que usas no teu mapa

        lock.writeLock().lock();
        try {
            // Adiciona a imagem ao mapa com contagem 0 e timestamp de criação
            imageReferenceCounts.put(keyName, new ImageCounterInfo(0, System.currentTimeMillis()));
            log.info("Image created and added to reference counts with 0 references: %s".formatted(keyName));
        } finally {
            lock.writeLock().unlock();
        }
        // --- FIM DA NOVA LÓGICA ---

        return ok(UriBuilder.fromUri(uid).path(iid).build().toASCIIString());
    }

    private static void createPathDirectories(String uid) {
        try {
            Files.createDirectories(Paths.get(IMAGES_DIR, uid));
        } catch (IOException e) {
            log.severe("Unable to create directories for user %s".formatted(uid));
            throw new RuntimeException(e);
        }
    }

    public void storeImage(Path path, byte[] content) {
        try {
            lock.writeLock().lock();
            tryToStoreImage(path, content);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void tryToStoreImage(Path path, byte[] content) {
        try {
            Files.deleteIfExists(path);
            Files.write(path, content);
        } catch (IOException e) {
            log.severe("Unable to store image %s".formatted(path));
            throw new RuntimeException(e);
        }
    }

    @Override
    public Result<byte[]> getImage(String uid, String iid) {
        log.info("getImage(uid -> %s, iid -> %s)".formatted(uid, iid));
        if (uid == null || iid == null)
            return error(BAD_REQUEST);
        var path = Paths.get(IMAGES_DIR, uid, iid);
        return retrieveImage(path);
    }

    private Result<byte[]> retrieveImage(Path path) {
        try {
            lock.readLock().lock();
            return tryToRetrieveImage(path);
        } finally {
            lock.readLock().unlock();
        }
    }

    private Result<byte[]> tryToRetrieveImage(Path path) {
        if (!Files.exists(path))
            return error(NOT_FOUND);
        try {
            return ok(Files.readAllBytes(path));
        } catch (IOException e) {
            log.severe("Unable to read image file with path %s".formatted(path.toString()));
            throw new RuntimeException(e);
        }
    }

    @Override
    public Result<Void> deleteImage(String uid, String iid, String pwd) {
        log.info("deleteImage(uid -> %s, iid -> %s, pwd -> %s)\n".formatted(uid, iid, pwd));
        if (iid == null)
            return error(BAD_REQUEST);
        var uRes = users.getUser(uid, pwd);
        if (!uRes.isOK())
            return error(uRes.error());
        Path path = Paths.get(IMAGES_DIR, uid, iid);
        return deleteValidImg(path);
    }

    @Override
    public Result<Void> deleteImageUponUserOrPostRemoval(@NotNull String uid, @NotNull String iid) {
        //log.info("Deleting image from removed entity: uid %s, iid %s\n".formatted(uid, iid));
        Path path = Paths.get(IMAGES_DIR, uid, iid);
        return deleteValidImg(path);
    }

    private Result<Void> deleteValidImg(Path path) {
        log.info("Actually deleting image with path %s".formatted(path.toString()));
        try {
            lock.writeLock().lock();
            return tryToDeleteValidImg(path);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Result<Void> tryToDeleteValidImg(Path path) {
        try {
            var deleted = Files.deleteIfExists(path);
            if (!deleted)
                return error(NOT_FOUND);
            String[] parts = path.toString().split("/");
            String uid = parts[parts.length - 2];
            String iid = parts[parts.length - 1];

            String x = "%s://%s:%s/%s/%s/%s";
            //String mediaUrl = x.formatted("https", InetAddress.getLocalHost().getHostName(), "8080", "rest", "image", UriBuilder.fromUri(uid).path(iid).build().toASCIIString());
            publisher.publish("image-deletion-events-2", iid, String.valueOf(System.currentTimeMillis()));
            return ok();
        } catch (IOException e) {
            log.severe("Unable to delete image with path %s".formatted(path.toString()));
            throw new RuntimeException(e);
        }
    }

    public Result<Void> teardown() {
        var dir = new File(IMAGES_DIR);
        File[] images = dir.listFiles();
        for (File img : images) {
            img.delete();
        }
        return ok();
    }

    private void processImageDeletion(String mediaUrl, long deletionTimestamp) {
        //log.info("Processing image deletion for mediaUrl: %s, deletionTimestamp: %d".formatted(mediaUrl, deletionTimestamp));
        long currentTime = System.currentTimeMillis();
        if (currentTime - deletionTimestamp > 30000 && !isImageReferenced(mediaUrl)) {

            String[] parts = mediaUrl.split("/");
            var uid = parts[parts.length - 2];
            var iid = parts[parts.length - 1];
            Path path = Paths.get(IMAGES_DIR, uid, iid);
            deleteValidImg(path);
        }
    }

    private boolean isImageReferenced(String imageId) {
        //log.info("Checking if image %s is referenced in posts.".formatted(imageId));
        setContent(ContentClient.getInstance());
        var postsResult = content.getPostsByImage(imageId);
        if (!postsResult.isOK()) {
            log.severe("Failed to retrieve posts while checking image reference.");
            return false;
        }

        List<String> posts = postsResult.value();
        return !posts.isEmpty(); // No matching mediaUrl found
    }

    public void startImageDeletionPublisher() {
        KafkaUtils.createTopic("image-deletion-events-2");
        this.publisher = KafkaPublisher.createPublisher("kafka:9092");
    }

    @Override
    public void onReceive(ConsumerRecord<String, String> r) {
        log.info("Received image reference event: %s -> %s".formatted(r.key(), r.value())); //
        String mediaUrlFromKafka = r.key(); // key é a mediaUrl completa do Content service
        String eventType = r.value(); // value é "INCREMENT" ou "DECREMENT"

        String[] parts = mediaUrlFromKafka.split("/");
        // Ajustar a extração de uid/iid da mediaUrl completa do Content
        // Assumindo formato: http://<ip>:<port>/images/media/<userId>/<imageId>
        String userId = parts[parts.length - 2]; //
        String imageId = parts[parts.length - 1]; //
        String keyName = String.format("%s/%s", userId, imageId); // A chave interna do teu mapa

        if (imageId == null || imageId.isEmpty()) {
            log.warning("Received event with invalid mediaUrl key: " + mediaUrlFromKafka);
            return;
        }

        lock.writeLock().lock();
        try {
            imageReferenceCounts.compute(keyName, (key, info) -> {
                long currentTimestamp = System.currentTimeMillis();
                if (info == null) {
                    // Esta imagem não estava no mapa (provavelmente criada noutra instância do Image ou referência inicial)
                    info = new ImageCounterInfo(0, currentTimestamp);
                    log.info("First reference event for new image %s. Initializing count.".formatted(keyName));
                }

                if ("INCREMENT".equals(eventType)) {
                    info.increment();
                    log.info("Incrementing count for image %s: %d -> %d".formatted(imageId, info.getCount() - 1, info.getCount()));
                } else if ("DECREMENT".equals(eventType)) {
                    info.decrement();
                    log.info("Decrementing count for image %s: %d -> %d".formatted(imageId, info.getCount() + 1, info.getCount()));
                } else {
                    log.warning("Unknown event type: %s for image %s".formatted(eventType, imageId));
                }
                return info;
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void startImageReferenceListener() {
        this.subscriber = KafkaSubscriber.createSubscriber("kafka:9092", List.of("image-reference-events")); // NOVO TÓPICO
        subscriber.start(this); // O próprio JavaImage agora é o RecordProcessor
    }

    private void startDeletionScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            log.info("Running scheduled image deletion task...");
            long currentTime = System.currentTimeMillis();
            lock.writeLock().lock(); // Proteger acesso ao mapa e aos ficheiros
            try {
                Iterator<Map.Entry<String, ImageCounterInfo>> iterator = imageReferenceCounts.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, ImageCounterInfo> entry = iterator.next();
                    String uidANDiid = entry.getKey();
                    ImageCounterInfo info = entry.getValue();

                    if (info.getCount() == 0 && (currentTime - info.getCreationTimestamp()) > DELETION_GRACE_PERIOD_MILLIS) {
                        log.info("Image %s has 0 references and grace period passed, attempting deletion.".formatted(uidANDiid));
                        String iid = uidANDiid.split("/")[1];
                        String uid = uidANDiid.split("/")[0];
                        Path path = Paths.get(IMAGES_DIR, uid, iid);

                        try {
                            deleteValidImg(path);
                            iterator.remove(); // Remove do mapa após apagar
                            log.info("Image %s deleted from file system and removed from counts.".formatted(iid));
                        } catch (Exception e) {
                            log.severe("Failed to delete image %s: %s".formatted(iid, e.getMessage()));
                        }
                    } else if (info.getCount() == 0) {
                        log.info("Image %s has 0 references but grace period not yet passed (%dms remaining).".formatted(uidANDiid, DELETION_GRACE_PERIOD_MILLIS - (currentTime - info.getCreationTimestamp())));
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
        }, 0, DELETION_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // Adicionar um metodo para parar o scheduler quando o serviço Image for encerrado (importante para shutdown limpo)
    public void stopScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
