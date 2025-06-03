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
import java.util.List;
import java.util.UUID;
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

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public JavaImage() {
        if (content == null)
            log.info("Content service not set, using default implementation.");
        if (users == null)
            log.info("Users service not set, using default implementation.");
        startImageDeletionPublisher();
        startImageDeletionListener();
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

    public void setPublisher(KafkaPublisher publisher) {
        this.publisher = publisher;
    }

    public void setSubscriber(KafkaSubscriber subscriber) {
        this.subscriber = subscriber;
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
        log.info("Received image deletion event: %s -> %s".formatted(r.key(), r.value()));
        String mediaUrl = r.key();
        long deletionTimestamp = Long.parseLong(r.value());
        String iid = mediaUrl.split("/")[mediaUrl.split("/").length - 1];
        String uid = mediaUrl.split("/")[mediaUrl.split("/").length - 2];
        Path path = Paths.get(IMAGES_DIR, uid, iid);
        deleteValidImg(path);
    }

    public void startImageDeletionListener() {
        this.subscriber = KafkaSubscriber.createSubscriber("kafka:9092", List.of("image-deletion-events"));
        subscriber.start(this);
    }
}
