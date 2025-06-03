package impl.kafka.imageEvent;

public class PostImageEvent {
    private String eventType; // "POST_CREATED", "POST_UPDATED", "POST_DELETED"
    private String postId;
    private String imageId;
    private long timestamp;

    public PostImageEvent(String eventType, String postId, String imageId, long timestamp) {
        this.eventType = eventType;
        this.postId = postId;
        this.imageId = imageId;
        this.timestamp = timestamp;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPostId() {
        return postId;
    }

    public String getImageId() {
        return imageId;
    }

    public long getTimestamp() {
        return timestamp;
    }

}

