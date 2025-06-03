package server.grpc;

import client.ImageClient;
import network.ServiceAnnouncer;

import javax.net.ssl.KeyManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.util.Optional;
import java.util.logging.Logger;

public class GrpcImageServer {

    private static final Logger log = Logger.getLogger(GrpcUsersServer.class.getName());

    public static final int PORT = 9000;

    public static void main(String[] args) {
        launchServer(PORT);
    }

    public static void launchServer(int port) {
        launchServer(port, Optional.empty());
    }

    public static void launchServer(int port, long period) {
        launchServer(port, Optional.of(period));
    }

    private static void launchServer(int port, Optional<Long> period) {
        try {
            String keyStoreFilename = System.getProperty("javax.net.ssl.keyStore");
            String keyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            FileInputStream input = new FileInputStream(keyStoreFilename);
            keyStore.load(input, keyStorePassword.toCharArray());
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, keyStorePassword.toCharArray());

            var serverURI = GrpcServerUtils.computeServerUri(port);
            announceService(period, serverURI);
            var stub = new GrpcImageStub(serverURI, null, false);
            log.info(String.format("Image gRPC Server ready @ %s\n", serverURI));
            GrpcServerUtils.launchServer(port, stub, keyManagerFactory);
        } catch (Exception e) {
            log.severe("Unable to launch gRPC server at port %d".formatted(port));
            throw new RuntimeException(e);
        }
    }

    private static void announceService(Optional<Long> period, String serverURI) throws IOException {
        if (period.isPresent())
            new ServiceAnnouncer(ImageClient.SERVICE, serverURI, period.get());
        else
            new ServiceAnnouncer(ImageClient.SERVICE, serverURI);
    }

}
