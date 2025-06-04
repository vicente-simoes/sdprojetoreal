package server.rest;

import api.Post;
import api.java.Result;
import api.rest.RestContent;
import impl.JavaContent;
import client.ImageClient;
import client.UsersClient;
import impl.kafka.utils.SyncPoint;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.*;
import network.DataModelAdaptor;

import java.net.URI;
import java.util.List;

import static network.DataModelAdaptor.incorporateUrlToId;
import static server.rest.RestServerUtils.statusCodeToException;
import static server.rest.RestServerUtils.wrapResult;

public class ContentResource implements RestContent {

    @Context
    private UriInfo uri;

    private final JavaContent contents = new JavaContent();

    public ContentResource() {
        contents.setUsers(UsersClient.getInstance());
        contents.setImages(ImageClient.getInstance());
    }

    @Override
    public Response createPost(long version, Post post, String userPassword) {
        hideParentUrl(post);
        Result<String> res = contents.createPost(post, userPassword);
        return getResponse(res);
    }

    private static void hideParentUrl(Post post) {
        var parentUrl = post.getParentUrl();
        if (parentUrl != null)
            post.setParentUrl(DataModelAdaptor.extractIdFromUrl(parentUrl));
    }

    @Override
    public Response getPosts(long version, long timestamp, String sortOrder) {
        Result<List<String>> res = contents.getPosts(timestamp, sortOrder);
        return getResponse(res);
    }

    @Override
    public Response getPost(long version, String postId) {
        var res = contents.getPost(postId);
        if (!res.isOK()) {
            throw new WebApplicationException(Response.status(statusCodeToException(res.error()).getResponse().getStatus())
                    .header(HEADER_VERSION, SyncPoint.getSyncPoint().getVersion()).build());
        }
        var post = res.value();
        incorporateParentUrl(post);

        return Response.ok()
                .header(HEADER_VERSION, SyncPoint.getSyncPoint().getVersion())
                .encoding(MediaType.APPLICATION_JSON)
                .entity(res.value())
                .build();
    }

    private void incorporateParentUrl(Post post) {
        var parentId = post.getParentUrl();
        if (parentId != null) {
            var parentUrl = incorporateUrlToId(uri.getBaseUri(), parentId);
            post.setParentUrl(parentUrl);
        }
    }

    @Override
    public Response getPostAnswers(long version, String postId, long maxTimeout) {
        var res = contents.getPostAnswers(postId, maxTimeout);
        return getResponse(res);
    }

    @Override
    public Response updatePost(long version, String postId, String userPassword, Post post) {
        var res = contents.updatePost(postId, userPassword, post);
        return getResponse(res);
    }

    @Override
    public Response deletePost(long version, String postId, String userPassword) {
        var res = contents.deletePost(postId, userPassword);
        return getResponse(res);
    }

    @Override
    public Response upVotePost(long version, String postId, String userId, String userPassword) {
        var res = contents.upVotePost(postId, userId, userPassword);
        return getResponse(res);
    }

    @Override
    public Response removeUpVotePost(long version, String postId, String userId, String userPassword) {
        var res = contents.removeUpVotePost(postId, userId, userPassword);
        return getResponse(res);
    }

    @Override
    public Response downVotePost(long version, String postId, String userId, String userPassword) {
        var res = contents.downVotePost(postId, userId, userPassword);
        return getResponse(res);
    }

    @Override
    public Response removeDownVotePost(long version, String postId, String userId, String userPassword) {
        var res = contents.removeDownVotePost(postId, userId, userPassword);
        return getResponse(res);
    }

    @Override
    public Response getupVotes(long version, String postId) {
        var res = contents.getupVotes(postId);
        return getResponse(res);
    }

    @Override
    public Response getDownVotes(long version, String postId) {
        var res = contents.getDownVotes(postId);
        return getResponse(res);
    }

    @Override
    public Response forgetUser(long version, String uid) {
        var res = contents.forgetUser(uid);
        return getResponse(res);
    }

    @Override
    public Response getPostsByImage(long version, String mediaUrl) {
        var res = contents.getPostsByImage(mediaUrl);
        return getResponse(res);
    }

    private Response getResponse(Result<?> res) {
        if (!res.isOK()) {
            throw new WebApplicationException(Response.status(statusCodeToException(res.error()).getResponse().getStatus())
                    .header(HEADER_VERSION, SyncPoint.getSyncPoint().getVersion()).build());
        }
        return Response.ok()
                .header(HEADER_VERSION, SyncPoint.getSyncPoint().getVersion())
                .encoding(MediaType.APPLICATION_JSON)
                .entity(res.value())
                .build();
    }
}

