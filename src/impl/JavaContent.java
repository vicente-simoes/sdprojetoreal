package impl;

import api.Post;
import api.Vote;
import api.java.Content;
import api.java.Image;
import api.java.Result;
import api.java.Users;
import impl.kafka.kafka.KafkaPublisher;
import impl.kafka.kafka.KafkaSubscriber;
import impl.kafka.kafka.KafkaUtils;
import impl.kafka.kafka.RecordProcessor;
import jakarta.validation.constraints.NotNull;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.hibernate.Session;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

import static api.java.Result.ErrorCode.*;
import static api.java.Result.*;
import static network.DataModelAdaptor.extractIdFromUrl;

public class JavaContent implements Content, RecordProcessor {

    private static final Logger log = Logger.getLogger(JavaContent.class.getName());

    private static final String POST_TABLE = Post.class.getSimpleName();

    private static final String VOTE_TABLE = Vote.class.getSimpleName();
    
    private final Hibernate db = Hibernate.getInstance();

    private Users users;

    private Image images;

    private KafkaPublisher publisher;

    private KafkaSubscriber imageSubscriber;
    private KafkaSubscriber replicationSubscriber;

    private static final String TOPIC_B = "image-reference-events";

    private volatile long lastCommittedKafkaOffset = 0;

    private static final ConcurrentMap<String, Object> barriers = new ConcurrentHashMap<>();

    public JavaContent() {
        //startImageDeletionPublisher();
        KafkaUtils.createTopic(TOPIC_B); // Novo tópico
        this.publisher = KafkaPublisher.createPublisher("kafka:9092");
        startKafkaListener();
    }

    public void setUsers(Users users) {
        this.users = users;
    }

    public void setImages(Image images) {
        this.images = images;
    }

    public long getLastCommittedKafkaOffset() {
        return lastCommittedKafkaOffset;
    }

    @Override
    public Result<String> createPost(Post p, String pwd) {
        log.info("createPost(post -> %s, pwd -> %s)\n".formatted(p, pwd));
        if (p == null || p.getAuthorId() == null)
            return error(BAD_REQUEST);
        if (p.getParentUrl() != null && !hasPost(p.getParentUrl()))
            return error(NOT_FOUND);
        var uRes = users.getUser(p.getAuthorId(), pwd);
        if (!uRes.isOK())
            return error(uRes.error());
        var pid = UUID.randomUUID().toString();
        p.setPostId(pid);
        p.setCreationTimestamp(System.currentTimeMillis());
        db.persist(p);
        if (p.getMediaUrl() != null && !p.getMediaUrl().isEmpty()) {
            // Envia evento de INCREMENTO
            long offset = publisher.publish("image-reference-events", p.getMediaUrl(), "INCREMENT"); // Alterado aqui
            lastCommittedKafkaOffset = offset;
            log.info("Sent INCREMENT event for image: %s".formatted(p.getMediaUrl()));
        }
        if (p.getParentUrl() != null)
            notifyGetPostAnswers(p.getParentUrl());

        // Publish replication event
        String replicationMessage = "CREATE_POST&&&%s&&&%s&&&%d&&&%s&&&%s&&&%s&&&%d&&&%d".formatted(
                pid, p.getAuthorId(), p.getCreationTimestamp(), p.getContent(),
                p.getMediaUrl() , p.getParentUrl(), p.getUpVote(), p.getDownVote());
        long offset = publisher.publish("replication-update-event", pid, replicationMessage);
        lastCommittedKafkaOffset = offset;
        log.info("Published replication event for post creation: %s".formatted(replicationMessage + "\n"));

        return ok(pid);
    }

    private static void notifyGetPostAnswers(String parentId) {
        var barrier = barriers.get(parentId);
        if (barrier != null) {
            synchronized (barrier) {
                barrier.notifyAll();
            }
        }
    }

    @Override
    public Result<List<String>> getPosts(long timestamp, String sortOrder) {
        log.info("getPosts(timestamp-> %d, sortOrder-> %s)\n".formatted(timestamp, sortOrder));
        var res = getPostsQuery(timestamp, sortOrder);
        if (!res.isOK())
            return error(res.error());
        var query = res.value();
        var posts = db.sql(query, String.class);
        log.info("retrieved %d top level posts".formatted(posts.size()));
        return ok(posts);
    }

    private Result<String> getPostsQuery(long timestamp, String sortOrder) {
        var timestampPlaceholder = timestamp > 0 ? "AND p.creationTimestamp >= " + timestamp : "";
        if (sortOrder == null || sortOrder.isEmpty())
            return ok("""
                    SELECT p.postId
                    FROM %s p
                    WHERE p.parentUrl IS NULL %s
                    ORDER BY p.creationTimestamp
                    """.formatted(POST_TABLE, timestampPlaceholder));
        return switch (sortOrder) {
            case Content.MOST_UP_VOTES -> ok("""
                    SELECT p.postId
                    FROM %s p
                    LEFT JOIN %s v
                    ON p.postId = v.postId
                    AND v.isUpvote=1
                    WHERE p.parentUrl IS NULL %s
                    GROUP BY p.postId
                    ORDER BY COALESCE(COUNT(v.postId), 0) DESC, p.postId ASC
                    """.formatted(POST_TABLE, VOTE_TABLE, timestampPlaceholder));
            case Content.MOST_REPLIES -> ok("""
                    SELECT p.postId
                    FROM %s p
                    LEFT JOIN %s p2
                    ON p.postId = p2.parentUrl
                    WHERE p.parentUrl IS NULL %s
                    GROUP BY p.postId
                    ORDER BY COALESCE(COUNT(p2.parentUrl), 0) DESC, p.postId ASC
                    """.formatted(POST_TABLE, POST_TABLE, timestampPlaceholder));
            default -> error(BAD_REQUEST);
        };
    }

    public Result<Post> getLocalPost(String pid) {
        if (pid == null)
            return error(BAD_REQUEST);
        var p = db.get(Post.class, pid);
        if (p == null)
            return error(NOT_FOUND);
        return ok(p);
    }

    @Override
    public Result<Post> getPost(String pid) {
        if (pid == null)
            return error(BAD_REQUEST);
        return db.execTransaction(s -> getPostTx(pid, s));
    }

    private static Result<Post> getPostTx(String pid, Session s) {
        var p = s.get(Post.class, pid);
        if (p == null)
            return error(NOT_FOUND);
        var voteQuery = """
                SELECT COUNT(*)
                FROM %s
                WHERE postId='%s'
                AND isUpvote=%d
                """;
        var upvoteQuery = voteQuery.formatted(VOTE_TABLE, pid, 1);
        var downvoteQuery = voteQuery.formatted(VOTE_TABLE, pid, 0);
        int upvotes = s.createNativeQuery(upvoteQuery, Integer.class).getSingleResult();
        int downvotes = s.createNativeQuery(downvoteQuery, Integer.class).getSingleResult();
        p.setUpVote(upvotes);
        p.setDownVote(downvotes);
        return ok(p);
    }

    @Override
    public Result<List<String>> getPostAnswers(String pid, long maxTimeout) {
        log.info("getPostAnswers(pid %s)".formatted(pid));
        if (pid == null)
            return error(BAD_REQUEST);
        if (!hasPost(pid))
            return error(NOT_FOUND);
        if (maxTimeout > 0)
            waitForNewAnswer(pid, maxTimeout);
        var query = """
                SELECT postId
                FROM %s
                WHERE parentUrl='%s'
                ORDER BY creationTimestamp
                """.formatted(POST_TABLE, pid);
        return ok(db.sql(query, String.class));
    }

    private boolean hasPost(String pid) {
        return db.get(Post.class, pid) != null;
    }

    private static void waitForNewAnswer(String pid, long maxTimeout) {
        log.info("blocking %d ms waiting for replies\n".formatted(maxTimeout));
        barriers.putIfAbsent(pid, new Object());
        var barrier = barriers.get(pid);
        synchronized (barrier) {
            try {
                barrier.wait(maxTimeout);
            } catch (InterruptedException ignored) {}
        }
    }

    @Override
    public Result<Post> updatePost(String pid, String pwd, Post updatedFields) {
        log.info("updatePost(pid -> %s, pwd -> %s, updatedFields -> %s)\n".formatted(pid, pwd, updatedFields));
        var pRes = this.getLocalPost(pid);
        if (!pRes.isOK())
            return error(pRes.error());
        var p = pRes.value();
        var uRes = users.getUser(p.getAuthorId(), pwd);
        if (!uRes.isOK())
            return error(uRes.error());
        if (updatedFields.getMediaUrl() == null || updatedFields.getMediaUrl().isEmpty()) {
            publisher.publish(TOPIC_B, p.getMediaUrl(), "DECREMENT"); // Envia evento de DECREMENTO
        } else {
            publisher.publish(TOPIC_B, p.getMediaUrl(), "DECREMENT"); // Envia evento de INCREMENTO
            publisher.publish(TOPIC_B, updatedFields.getMediaUrl(), "INCREMENT");
        }

        return db.execTransaction(s -> updatePostTx(pid, updatedFields, s));
    }

    // We need the get and the update in the same transaction so as not to squash other updates.
    private Result<Post> updatePostTx(String pid, Post updatedFields, Session s) {
        var p = s.get(Post.class, pid);    // In update we get the post twice. This is because we need to check if the user is the author of the post outside the transaction.
        if (p == null)
            return error(NOT_FOUND);
        if (hasVote(pid, s) || hasChildren(pid, s))
            return error(BAD_REQUEST);
        if (updatedFields.getContent() != null)
            p.setContent(updatedFields.getContent());
        if (updatedFields.getMediaUrl() != null) {
            p.setMediaUrl(updatedFields.getMediaUrl());
        }
        s.merge(p);
        return Result.ok(p);
    }

    private boolean hasVote(String pid, Session s) {
        var query = """
                SELECT COUNT(*)
                FROM %s
                WHERE postId='%s'
                """.formatted(VOTE_TABLE, pid);
        return s.createNativeQuery(query, Integer.class).getSingleResult() > 0;
    }

    private boolean hasChildren(String pid, Session s) {
        var query = """
                SELECT COUNT(*)
                FROM %s
                WHERE parentUrl='%s'
                """.formatted(POST_TABLE, pid);
        return s.createNativeQuery(query, Integer.class).getSingleResult() > 0;
    }

    @Override
    public Result<Void> deletePost(String pid, String pwd) {
        log.info("deletePost(pid -> %s, pwd -> %s)\n".formatted(pid, pwd));
        var pRes = getLocalPost(pid);
        if (!pRes.isOK())
            return error(pRes.error());
        var p = pRes.value();
        var uRes = users.getUser(p.getAuthorId(), pwd);
        if (!uRes.isOK())
            return error(uRes.error());
        var dRes = db.execTransaction(s -> deletePostTx(pid, s));
        if (!dRes.isOK())
            return error(dRes.error());
        var deletedIds = dRes.value();
        deleteAllVotes(deletedIds);
        String mediaUrl = p.getMediaUrl();
        if (mediaUrl != null && !mediaUrl.isEmpty()) {
            // Envia evento de DECREMENTO
            publisher.publish(TOPIC_B, mediaUrl, "DECREMENT"); // Alterado aqui
            log.info("Sent DECREMENT event for image: %s".formatted(mediaUrl));
        }
        return tryToDeletePostMedia(p);
    }

    /**
     * @return Returns a list with the ids of all deleted posts
     */
    private Result<List<String>> deletePostTx(String pid, Session s) {
        var p = s.get(Post.class, pid);
        if (p == null)
            return error(NOT_FOUND);
        var toDeleteIds = getDescendants(Collections.singleton(pid), s);
        toDeleteIds.add(pid);
        deleteAllPosts(toDeleteIds, s);
        return ok(toDeleteIds);
    }

    private static List<String> getDescendants(Collection<String> parents, Session s) {
        var children = getChildren(parents, s);
        if (children.isEmpty())
            return new ArrayList<>();
        var descendantIds = getDescendants(children, s);
        descendantIds.addAll(children);
        return descendantIds;
    }

    private static List<String> getChildren(Collection<String> parents, Session s) {
        var query = """
                SELECT postId
                FROM %s
                WHERE parentUrl IN (%s)
                """.formatted(POST_TABLE, joinPidsInQuery(parents));
        return s.createNativeQuery(query, String.class).list();
    }

    private static void deleteAllPosts(Collection<String> pids, Session s) {
        var deleteStatement = "DELETE FROM %s WHERE postId IN (%s)".formatted(POST_TABLE, joinPidsInQuery(pids));
        s.createNativeQuery(deleteStatement, Post.class).executeUpdate();
    }

    // Does not need to be in the same tx as the post delete.
    private void deleteAllVotes(Collection<String> pids) {
        var deleteStatement = "DELETE FROM %s WHERE postId IN (%s)".formatted(VOTE_TABLE, joinPidsInQuery(pids));
        db.execTransaction(s -> ok(s.createNativeQuery(deleteStatement, Vote.class).executeUpdate()));
    }

    private static String joinPidsInQuery(Collection<String> pids) {
        assert !pids.isEmpty();
        var pidsBrackets = pids.stream().map("'%s'"::formatted).toList();
        return String.join(",", pidsBrackets);
    }

    private Result<Void> tryToDeletePostMedia(Post p) {
        if (p.getMediaUrl() == null)
            return ok();
        var mediaUrl = p.getMediaUrl();
        var iid = extractIdFromUrl(p.getMediaUrl());
        var timestamp = System.currentTimeMillis();
        /*List<String> posts = getPostsByImage(iid).value();
        if (posts.isEmpty()) {
            log.info("No posts found with media URL: %s".formatted(mediaUrl));
            publisher.publish("image-deletion-events", mediaUrl, String.valueOf(timestamp));
        } else {
            log.info("Posts found with media URL: %s, not deleting image.".formatted(iid));
        }*/
        return ok(); //images.deleteImageUponUserOrPostRemoval(p.getAuthorId(), iid);
    }

    @Override
    public Result<Void> upVotePost(String pid, String uid, String pwd) {
        log.info("upVotePost(pid -> %s, uid -> %s, pwd -> %s)\n".formatted(pid, uid, pwd));
        var pRes = getLocalPost(pid);
        if (!pRes.isOK())
            return error(pRes.error());
        var uRes = users.getUser(uid, pwd);
        if (!uRes.isOK())
            return error(uRes.error());
        return db.execTransaction(s -> upvoteTx(pid, uid, s));
    }

    private Result<Void> upvoteTx(String pid, String uid, Session s) {
        if (s.get(Post.class, pid) == null)
            return error(NOT_FOUND);
        if (hasVoted(uid, pid, s))
            return error(CONFLICT);
        s.persist(new Vote(uid, pid, true));
        return ok();
    }

    @Override
    public Result<Void> removeUpVotePost(String pid, String uid, String pwd) {
        log.info("removeUpVotePost(pid -> %s, uid -> %s, pwd -> %s)\n".formatted(pid, uid, pwd));
        var pRes = getLocalPost(pid);
        if (!pRes.isOK())
            return error(pRes.error());
        var uRes = users.getUser(uid, pwd);
        if (!uRes.isOK())
            return error(uRes.error());
        return db.execTransaction(s -> removeUpvoteTx(pid, uid, s));
    }

    private Result<Void> removeUpvoteTx(String pid, String uid, Session s) {
        if (s.get(Post.class, pid) == null)
            return error(NOT_FOUND);
        if (!hasUpvoted(uid, pid, s))
            return error(CONFLICT);
        s.remove(new Vote(uid, pid, true));
        return ok();
    }

    @Override
    public Result<Void> downVotePost(String pid, String uid, String pwd) {
        log.info("downVotePost(pid -> %s, uid -> %s, pwd -> %s)\n".formatted(pid, uid, pwd));
        var pRes = getLocalPost(pid);
        if (!pRes.isOK())
            return error(pRes.error());
        var uRes = users.getUser(uid, pwd);
        if (!uRes.isOK())
            return error(uRes.error());
        return db.execTransaction(s -> downvoteTx(pid, uid, s));
    }

    private Result<Void> downvoteTx(String pid, String uid, Session s) {
        if (s.get(Post.class, pid) == null)
            return error(NOT_FOUND);
        if (hasVoted(uid, pid, s))
            return error(CONFLICT);
        s.persist(new Vote(uid, pid, false));
        return ok();
    }

    @Override
    public Result<Void> removeDownVotePost(String pid, String uid, String pwd) {
        log.info("removeDownVotePost(pid -> %s, uid -> %s, pwd -> %s)\n".formatted(pid, uid, pwd));
        var pRes = getLocalPost(pid);
        if (!pRes.isOK())
            return error(pRes.error());
        var uRes = users.getUser(uid, pwd);
        if (!uRes.isOK())
            return error(uRes.error());
        return db.execTransaction(s -> removeDownvoteTx(pid, uid, s));
    }

    private Result<Void> removeDownvoteTx(String pid, String uid, Session s) {
        if (s.get(Post.class, pid) == null)
            return error(NOT_FOUND);
        if (!hasDownvoted(uid, pid, s))
            return error(CONFLICT);
        s.remove(new Vote(uid, pid, false));
        return ok();
    }

    private boolean hasVoted(String uid, String pid, Session s) {
        var query = """
                SELECT COUNT(*)
                FROM %s
                WHERE voterId='%s' AND postId='%s'
                """.formatted(VOTE_TABLE, uid, pid);
        return s.createNativeQuery(query, Integer.class).getSingleResult() > 0;
    }

    private boolean hasUpvoted(String uid, String pid, Session s) {
        var query = """
                SELECT COUNT(*)
                FROM %s
                WHERE voterId='%s' AND postId='%s' AND isUpvote=1
                """.formatted(VOTE_TABLE, uid, pid);
        return s.createNativeQuery(query, Integer.class).getSingleResult() > 0;
    }

    private boolean hasDownvoted(String uid, String pid, Session s) {
        var query = """
                SELECT COUNT(*)
                FROM %s
                WHERE voterId='%s' AND postId='%s' AND isUpvote=0
                """.formatted(VOTE_TABLE, uid, pid);
        return s.createNativeQuery(query, Integer.class).getSingleResult() > 0;
    }

    @Override
    public Result<Integer> getupVotes(String pid) {
        log.info("getupVotes(pid -> %s)\n".formatted(pid));
        var res = getLocalPost(pid);
        if (!res.isOK())
            return error(res.error());
        return ok(getVotes(pid, true));
    }

    @Override
    public Result<Integer> getDownVotes(String pid) {
        log.info("getDownVotes(pid -> %s)\n".formatted(pid));
        var res = getLocalPost(pid);
        if (!res.isOK())
            return error(res.error());
        return ok(getVotes(pid, false));
    }

    private int getVotes(String pid, boolean isUpvote) {
        var isUpvoteNum = isUpvote ? 1 : 0;
        var query = """
                SELECT COUNT(*)
                FROM %s
                WHERE postId='%s' AND isUpvote=%d
                """.formatted(VOTE_TABLE, pid, isUpvoteNum);
        var res = db.execTransaction(s -> ok(s.createNativeQuery(query, Integer.class).getSingleResult()));
        return res.value();
    }

    // Internal use function. Delete user information in posts
    @Override
    public Result<Void> forgetUser(@NotNull String uid) {
        var updateStatement = """
                UPDATE %s
                SET authorId=NULL
                WHERE authorId='%s'
                """.formatted(POST_TABLE, uid);
        db.execTransaction(s -> ok(s.createNativeQuery(updateStatement, Post.class).executeUpdate()));
        var deleteStatement = """
                DELETE FROM %s
                WHERE voterId='%s'
                """.formatted(VOTE_TABLE, uid);
        db.execTransaction(s -> ok(s.createNativeQuery(deleteStatement, Post.class).executeUpdate()));
        return ok();
    }

    @Override
    public Result<List<String>> getPostsByImage(String imageUrl) {
        String query = "SELECT postId FROM " + POST_TABLE + " WHERE mediaUrl LIKE '%" + imageUrl + "'";
        var posts = db.sql(query, String.class);
        //log.info("retrieved %d posts with image %s".formatted(posts.size(), imageUrl));
        return ok(posts);
    }

    public void startKafkaListener() {
        this.imageSubscriber = KafkaSubscriber.createSubscriber("kafka:9092", List.of("image-deletion-events-2"));
        imageSubscriber.start(this);
        this.replicationSubscriber = KafkaSubscriber.createSubscriber("kafka:9092", List.of("replication-update-event"));
        replicationSubscriber.start(this);
    }

    @Override
    public void onReceive(ConsumerRecord<String, String> r) {
        String topic = r.topic();
        switch (topic) {
            case "image-deletion-events-2" -> {
                handleImageDeletionEvent(r);
            }
            case "replication-update-event" -> {
                handleReplicationUpdateEvent(r);
            }
            default -> log.warning("Received unknown topic: %s".formatted(topic));
        }
        this.lastCommittedKafkaOffset = r.offset();
    }

    private void handleImageDeletionEvent(ConsumerRecord<String, String> r) {
        String iid = r.key();
        long deletionTimestamp = Long.parseLong(r.value());
        log.info("Handling image deletion event for image ID: %s at timestamp: %d".formatted(iid, deletionTimestamp));
        removeMediaFromPosts(iid);
    }

    private void removeMediaFromPosts(String iid) {
        log.info("Removing media from posts for URL: %s".formatted(iid));
        var postsResult = getPostsByImage(iid);
        if (!postsResult.isOK()) {
            log.severe("Failed to retrieve posts while removing media.");
            return;
        }
        List<String> posts = postsResult.value();
        if (posts.isEmpty()) {
            log.info("No posts found with media URL: %s".formatted(iid));
            return;
        }
        for (String postId : posts) {
            var postRes = getLocalPost(postId);
            if (postRes.isOK()) {
                Post post = postRes.value();
                post.setMediaUrl(null);
                db.update(post);
                log.info("Removed media from post: %s".formatted(postId));
            } else {
                log.warning("Failed to retrieve post %s while removing media.".formatted(postId));
            }
        }
    }

    private void handleReplicationUpdateEvent(ConsumerRecord<String, String> r) {
        log.info("Received replication update event: %s -> %s".formatted(r.key(), r.value()));
        String postId = r.key();
        String message = r.value();
        String [] parts = message.split("&&&");
        String operation = parts[0];
        switch (operation) {
            case "CREATE_POST" -> {
                //String postId = parts[1]; ja vem na key
                String authorId = parts[2];
                long creationTimestamp = Long.parseLong(parts[3]);
                String content = parts[4];
                String mediaUrl = parts[5];
                String parentUrl = parts[6];
                int upVote = Integer.parseInt(parts[7]);
                int downVote = Integer.parseInt(parts[8]);
                Post post = new Post(postId, authorId, creationTimestamp, content, mediaUrl, parentUrl, upVote, downVote);

                db.persist(post);
                if (post.getParentUrl() != null)
                    notifyGetPostAnswers(post.getParentUrl());
            }
            case "UPDATE_POST" -> {

            }
            case "DELETE_POST" -> {

            }
            case "UPVOTE_POST" -> {

            }
            case "REMOVE_UPVOTE_POST" -> {

            }
            case "DOWNVOTE_POST" -> {

            }
            case "REMOVE_DOWNVOTE_POST" -> {

            }
            default -> log.warning("Unknown operation in replication update event: %s".formatted(operation));
        }
    }
}
