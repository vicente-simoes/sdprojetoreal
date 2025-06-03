package server.grpc;

import api.java.Result;
import io.grpc.*;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import server.ServerUtils;

import javax.net.ssl.KeyManagerFactory;
import java.io.IOException;
import java.net.UnknownHostException;

import static io.grpc.Status.*;

public class GrpcServerUtils {

    private static final String COMM_PROTOCOL = "grpc";

    static String computeServerUri(int port) throws UnknownHostException {
        return ServerUtils.computeServerUri(COMM_PROTOCOL, port, ServerUtils.CommInterface.GRPC);
    }

    static void launchServer(int port, BindableService stub, KeyManagerFactory keyManagerFactory) throws InterruptedException, IOException {
        SslContext context = GrpcSslContexts.configure(SslContextBuilder.forServer(keyManagerFactory)).build();
        Server server = NettyServerBuilder.forPort(port)
                .addService(stub)
                .sslContext(context)
                .build();

        //ServerCredentials cred = InsecureServerCredentials.create();
        //Server server = Grpc.newServerBuilderForPort(port, cred).addService(stub).build();
        server.start().awaitTermination();
    }

    static <T, V> void unwrapResult(StreamObserver<T> obs, Result<V> res, Runnable r) {
        if (!res.isOK()) {
            obs.onError(errorCodeToStatus(res.error()));
        } else {
            r.run();
            obs.onCompleted();
        }
    }

    static StatusException errorCodeToStatus(Result.ErrorCode err) {
        Status s = switch (err) {
            case BAD_REQUEST -> INVALID_ARGUMENT;
            case NOT_FOUND -> NOT_FOUND;
            case FORBIDDEN -> PERMISSION_DENIED;
            case CONFLICT -> ALREADY_EXISTS;
            default -> INTERNAL;
        };
        return s.asException();
    }


}
