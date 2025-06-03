package server.grpc;

import client.ImageClient;
import network.ServiceAnnouncer;

import javax.net.ssl.KeyManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.KeyStore;
import java.util.Optional;
import java.util.logging.Logger;

public class GrpcImgurServer {
    private static final Logger log = Logger.getLogger(GrpcUsersServer.class.getName());

    public static final int PORT = 9000;

    public static void main(String[] args) {
        String flag = args[0];
        log.info("Starting Imgur gRPC Server with args: " + flag);
        launchServer(PORT, flag);
    }

    public static void launchServer(int port, String flag) {
        launchServer(port, Optional.empty(), flag);
    }

    public static void launchServer(int port, long period, String flag) {
        launchServer(port, Optional.of(period), flag);
    }

    private static void launchServer(int port, Optional<Long> period, String flag) {
        try {
            String keyStoreFilename = System.getProperty("javax.net.ssl.keyStore");
            String keyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            FileInputStream input = new FileInputStream(keyStoreFilename);
            keyStore.load(input, keyStorePassword.toCharArray());
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, keyStorePassword.toCharArray());

            var serverURI = GrpcServerUtils.computeServerUri(port);
            URI uri = new URI(serverURI);
            announceService(period, serverURI);
            String albumFile = "album_" + uri.getHost() + ".txt";
            boolean createNewAlbum = Boolean.parseBoolean(flag);
            var stub = new GrpcImageStub(serverURI, albumFile, createNewAlbum);
            stub.setUseImgurService(true); // Switch to Imgur service
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
