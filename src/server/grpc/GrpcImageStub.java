package server.grpc;

import api.java.Image;
import client.ContentClient;
import impl.JavaImage;
import client.UsersClient;
import com.google.protobuf.ByteString;
import impl.JavaImgur;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.accesslog.v3.ComparisonFilter;
import jakarta.ws.rs.core.UriBuilder;
import network.grpc.ImageGrpc;

import java.util.Optional;
import java.util.logging.Logger;

import static network.grpc.ImageProtoBuf.*;
import static server.grpc.GrpcServerUtils.unwrapResult;

public class GrpcImageStub implements ImageGrpc.AsyncService, BindableService {

    private static final Logger log = Logger.getLogger(GrpcUsersServer.class.getName());


    private final String baseUri;

    private final JavaImage localImageService;
    private final JavaImgur imgurImageService;

    private boolean useImgurService; // Flag to toggle between services

    public GrpcImageStub(String baseUri, String albumFile, boolean createNewAlbum) {
        this.baseUri = baseUri;
        localImageService = new JavaImage();
        localImageService.setUsers(UsersClient.getInstance());
        localImageService.setContent(ContentClient.getInstance());
        if (albumFile != null && !albumFile.isEmpty()) {
            imgurImageService = new JavaImgur(createNewAlbum, albumFile);
            imgurImageService.setUsers(UsersClient.getInstance());
        } else {
            imgurImageService = null;
            log.warning("Imgur service is not initialized because albumFile is null or empty.");
        }
        useImgurService = false;
    }

    public void setUseImgurService(boolean useImgurService) {
        this.useImgurService = useImgurService;
    }

    private Image getCurrentService() {
        return useImgurService ? imgurImageService : localImageService;
    }

    @Override
    public ServerServiceDefinition bindService() {
        return ImageGrpc.bindService(this);
    }

    @Override
    public void createImage(CreateImageArgs req, StreamObserver<CreateImageResult> obs) {

            var content = req.getImageContents().toByteArray();
            var res = getCurrentService().createImage(req.getUserId(), content, req.getPassword());
            unwrapResult(obs, res, () -> {
                var relativeUri = res.value();
                var uri = UriBuilder.fromUri(baseUri).path(relativeUri).build().toASCIIString();
                obs.onNext(CreateImageResult.newBuilder().setImageId(uri).build());
            });
    }

    @Override
    public void getImage(GetImageArgs req, StreamObserver<GetImageResult> obs) {
        var res = getCurrentService().getImage(req.getUserId(), req.getImageId());
        unwrapResult(obs, res, () -> {
            var content = res.value();
            var grpcContent = ByteString.copyFrom(content);
            obs.onNext(GetImageResult.newBuilder().setData(grpcContent).build());
        });
    }

    @Override
    public void deleteImage(DeleteImageArgs req, StreamObserver<DeleteImageResult> obs) {
        var res = getCurrentService().deleteImage(req.getUserId(), req.getImageId(), req.getPassword());
        unwrapResult(obs, res, () -> obs.onNext(DeleteImageResult.getDefaultInstance()));
    }

    @Override
    public void deleteImageUponUserOrPostRemoval(DelUponUsrRemArgs req, StreamObserver<DelUponUsrRemResult> obs) {
        var res = getCurrentService().deleteImageUponUserOrPostRemoval(req.getUserId(), req.getImageId());
        unwrapResult(obs, res, () -> obs.onNext(DelUponUsrRemResult.getDefaultInstance()));
    }

    @Override
    public void teardown(TeardownArgs req, StreamObserver<TeardownResult> obs) {
        getCurrentService().teardown();
        obs.onNext(TeardownResult.getDefaultInstance());
        obs.onCompleted();
   }
}
