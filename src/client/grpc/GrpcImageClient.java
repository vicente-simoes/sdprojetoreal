package client.grpc;

import api.java.Image;
import api.java.Result;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannelBuilder;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import network.grpc.ImageGrpc;
import network.grpc.ImageProtoBuf;

import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.net.URI;
import java.security.KeyStore;

import static api.java.Result.ErrorCode.BAD_REQUEST;
import static api.java.Result.ErrorCode.FORBIDDEN;
import static api.java.Result.error;
import static api.java.Result.ok;
import static client.grpc.GrpcClientUtils.wrapRequest;

public class GrpcImageClient implements Image {

    static {
        LoadBalancerRegistry.getDefaultRegistry().register(new PickFirstLoadBalancerProvider());
    }

    private final ImageGrpc.ImageBlockingStub stub;

    public GrpcImageClient(URI serverURI) {
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
            //var channel = ManagedChannelBuilder.forAddress(serverURI.getHost(), serverURI.getPort()).enableRetry().usePlaintext().build();
            this.stub = ImageGrpc.newBlockingStub(channel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create gRPC Image client", e);
        }
    }

    @Override
    public Result<String> createImage(String uid, byte[] content, String pwd) {
        if (uid == null || content == null)
            return error(BAD_REQUEST);
        if (pwd == null)
            return error(FORBIDDEN);
        return wrapRequest(() -> {
            var args = ImageProtoBuf.CreateImageArgs.newBuilder()
                    .setUserId(uid)
                    .setImageContents(ByteString.copyFrom(content))
                    .setPassword(pwd)
                    .build();
            var res = stub.createImage(args);
            return ok(res.getImageId());
        });
    }

    @Override
    public Result<byte[]> getImage(String uid, String iid) {
        if (uid == null || iid == null)
            return error(BAD_REQUEST);
        return wrapRequest(() -> {
            var args = ImageProtoBuf.GetImageArgs.newBuilder()
                    .setUserId(uid)
                    .setImageId(iid)
                    .build();
            var res = stub.getImage(args);
            return ok(res.getData().toByteArray());
        });
    }

    @Override
    public Result<Void> deleteImage(String uid, String iid, String pwd) {
        if (uid == null || iid == null)
            return error(BAD_REQUEST);
        if (pwd == null)
            return error(FORBIDDEN);
        return wrapRequest(() -> {
            var args = ImageProtoBuf.DeleteImageArgs.newBuilder()
                    .setUserId(uid)
                    .setImageId(iid)
                    .setPassword(pwd)
                    .build();
            stub.deleteImage(args);
            return ok();
        });
    }

    @Override
    public Result<Void> deleteImageUponUserOrPostRemoval(String uid, String iid) {
        assert uid != null && iid != null;
        return wrapRequest(() -> {
            var args = ImageProtoBuf.DelUponUsrRemArgs.newBuilder()
                    .setUserId(uid)
                    .setImageId(iid)
                    .build();
            stub.deleteImageUponUserOrPostRemoval(args);
            return ok();
        });
    }

    @Override
    public Result<Void> teardown() {
        return wrapRequest(() -> {
            var args = ImageProtoBuf.TeardownArgs.getDefaultInstance();
            stub.teardown(args);
            return ok();
        });
    }
}
