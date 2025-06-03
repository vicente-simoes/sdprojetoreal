package client.grpc;

import api.Post;
import api.java.Content;
import api.java.Result;
import io.grpc.Channel;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannelBuilder;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import network.DataModelAdaptor;
import network.grpc.ContentGrpc;
import network.grpc.ContentProtoBuf.*;

import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.URI;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.List;

import static api.java.Result.ErrorCode.*;
import static api.java.Result.*;
import static client.grpc.GrpcClientUtils.wrapRequest;

public class GrpcContentClient implements Content {

    static {
        LoadBalancerRegistry.getDefaultRegistry().register(new PickFirstLoadBalancerProvider());
    }

    private final ContentGrpc.ContentBlockingStub stub;

    public GrpcContentClient(URI serverURI) {
        String trustStoreFilename = System.getProperty("javax.net.ssl.trustStore");
        String trustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            try (FileInputStream input = new FileInputStream(trustStoreFilename)) {
                trustStore.load(input, trustStorePassword.toCharArray());
            }

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);



            SslContext sslContext = GrpcSslContexts.configure(SslContextBuilder.forClient().trustManager(trustManagerFactory)).build();

            Channel channel = NettyChannelBuilder.forAddress(serverURI.getHost(), serverURI.getPort())
                    .sslContext(sslContext)
                    .enableRetry()
                    .build();

            //var channel = ManagedChannelBuilder.forAddress(serverUri.getHost(), serverUri.getPort()).enableRetry().usePlaintext().build();
            this.stub = ContentGrpc.newBlockingStub(channel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize trust manager", e);
        }
    }

    @Override
    public Result<String> createPost(Post p, String pwd) {
        if (p == null)
            return error(BAD_REQUEST);
        if (pwd == null)
            return error(FORBIDDEN);
        return wrapRequest(() -> {
            var args = CreatePostArgs.newBuilder()
                    .setPost(DataModelAdaptor.Post_to_GrpcPost(p))
                    .setPassword(pwd)
                    .build();
            return ok(stub.createPost(args).getPostId());
        });
    }

    @Override
    public Result<List<String>> getPosts(long timestamp, String sortOrder) {
        var finalSortOrder = sortOrder == null ? "" : sortOrder;
        return wrapRequest(() -> {
            var args = GetPostsArgs.newBuilder()
                    .setTimestamp(timestamp)
                    .setSortOrder(finalSortOrder)
                    .build();
            var res = stub.getPosts(args);
            List<String> pids = res.getPostIdList().stream().toList();
            return ok(pids);
        });
    }

    @Override
    public Result<Post> getPost(String pid) {
        if (pid == null)
            return error(BAD_REQUEST);
        return wrapRequest(() -> {
            var args = GetPostArgs.newBuilder().setPostId(pid).build();
            var res = stub.getPost(args);
            return ok(DataModelAdaptor.GrpcPost_to_Post(res));
        });
    }

    @Override
    public Result<List<String>> getPostAnswers(String pid, long maxTimeout) {
        if (pid == null)
            return error(BAD_REQUEST);
        return wrapRequest(() -> {
            var args = GetPostAnswersArgs.newBuilder().setPostId(pid).setTimeout(maxTimeout).build();
            var res = stub.getPostAnswers(args);
            List<String> pids = res.getPostIdList().stream().toList();
            return ok(pids);
        });
    }

    @Override
    public Result<Post> updatePost(String pid, String pwd, Post updatedFields) {
        if (pid == null || updatedFields == null)
            return error(BAD_REQUEST);
        if (pwd == null)
            return error(FORBIDDEN);
        return wrapRequest(() -> {
            var args = UpdatePostArgs.newBuilder()
                    .setPostId(pid)
                    .setPassword(pwd)
                    .setPost(DataModelAdaptor.Post_to_GrpcPost(updatedFields))
                    .build();
            var res = stub.updatePost(args);
            return ok(DataModelAdaptor.GrpcPost_to_Post(res));
        });
    }

    @Override
    public Result<Void> deletePost(String pid, String pwd) {
        if (pid == null)
            return error(BAD_REQUEST);
        if (pwd == null)
            return error(FORBIDDEN);
        return wrapRequest(() -> {
            var args = DeletePostArgs.newBuilder()
                    .setPostId(pid)
                    .setPassword(pwd)
                    .build();
            stub.deletePost(args);
            return ok();
        });
    }

    @Override
    public Result<Void> upVotePost(String pid, String uid, String pwd) {
        if (pid == null || uid == null)
            return error(BAD_REQUEST);
        if (pwd == null)
            return error(FORBIDDEN);
        return wrapRequest(() -> {
            var args = ChangeVoteArgs.newBuilder()
                    .setPostId(pid)
                    .setUserId(uid)
                    .setPassword(pwd)
                    .build();
            stub.upVotePost(args);
            return ok();
        });
    }

    @Override
    public Result<Void> removeUpVotePost(String pid, String uid, String pwd) {
        if (pid == null || uid == null)
            return error(BAD_REQUEST);
        if (pwd == null)
            return error(FORBIDDEN);
        return wrapRequest(() -> {
            var args = ChangeVoteArgs.newBuilder()
                    .setPostId(pid)
                    .setUserId(uid)
                    .setPassword(pwd)
                    .build();
            stub.removeUpVotePost(args);
            return ok();
        });
    }

    @Override
    public Result<Void> downVotePost(String pid, String uid, String pwd) {
        if (pid == null || uid == null)
            return error(BAD_REQUEST);
        if (pwd == null)
            return error(FORBIDDEN);
        return wrapRequest(() -> {
            var args = ChangeVoteArgs.newBuilder()
                    .setPostId(pid)
                    .setUserId(uid)
                    .setPassword(pwd)
                    .build();
            stub.downVotePost(args);
            return ok();
        });
    }

    @Override
    public Result<Void> removeDownVotePost(String pid, String uid, String pwd) {
        if (pid == null || uid == null)
            return error(BAD_REQUEST);
        if (pwd == null)
            return error(FORBIDDEN);
        return wrapRequest(() -> {
            var args = ChangeVoteArgs.newBuilder()
                    .setPostId(pid)
                    .setUserId(uid)
                    .setPassword(pwd)
                    .build();
            stub.removeDownVotePost(args);
            return ok();
        });
    }

    @Override
    public Result<Integer> getupVotes(String pid) {
        if (pid == null)
            return error(BAD_REQUEST);
        return wrapRequest(() -> {
            var args = GetPostArgs.newBuilder().setPostId(pid).build();
            return ok(stub.getUpVotes(args).getCount());
        });
    }

    @Override
    public Result<Integer> getDownVotes(String pid) {
        if (pid == null)
            return error(BAD_REQUEST);
        return wrapRequest(() -> {
            var args = GetPostArgs.newBuilder().setPostId(pid).build();
            return ok(stub.getDownVotes(args).getCount());
        });
    }

    @Override
    public Result<Void> forgetUser(String uid) {
        assert uid != null;
        return wrapRequest(() -> {
            var args = ForgetUserArgs.newBuilder().setUserId(uid).build();
            stub.forgetUser(args);
            return ok();
        });
    }

    @Override
    public Result<List<String>> getPostsByImage(String imageUrl) {
        if (imageUrl == null)
            return error(BAD_REQUEST);
        return wrapRequest(() -> {
            var args = GetPostByImageArgs.newBuilder().setImageUrl(imageUrl).build();
            var res = stub.getPostsByImage(args);
            List<String> pids = res.getPostIdList().stream().toList();
            return ok(pids);
        });
    }
}
