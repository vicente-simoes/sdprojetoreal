package impl;

import api.java.Image;
import api.java.Result;

import api.java.Users;
import com.github.scribejava.apis.ImgurApi;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.gson.Gson;
import impl.imgur.*;

import java.io.ByteArrayOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

import static api.java.Result.ErrorCode.BAD_REQUEST;
import static api.java.Result.error;
import static api.java.Result.ok;

public class JavaImgur implements Image {

    private static final Logger log = Logger.getLogger(JavaImage.class.getName());

    private static final String IMAGES_DIR = "./media";

    private Users users;
    

    private static final String apiKey = "e6e4b435d3382ba";
    private static final String apiSecret = "4f88a07a4342ca03727dc88405dc234c9a2eecec";
    private static final String accessTokenStr = "c0e909fbdf231ff064509984201362a2df8d4d08";

    private static final String CREATE_ALBUM_URL = "https://api.imgur.com/3/album";
    private static final String UPLOAD_IMAGE_URL = "https://api.imgur.com/3/image";
    private static final String ADD_IMAGE_TO_ALBUM_URL = "https://api.imgur.com/3/album/{{albumHash}}/add";

    private static final int HTTP_SUCCESS = 200;
    private static final String CONTENT_TYPE_HDR = "Content-Type";
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private final Gson json;
    private final OAuth20Service service;
    private final OAuth2AccessToken accessToken;

    private String albumId;

    public JavaImgur(boolean createNew, String albumIdPath) {
        json = new Gson();
        accessToken = new OAuth2AccessToken(accessTokenStr);
        service = new ServiceBuilder(apiKey).apiSecret(apiSecret).build(ImgurApi.instance());
        String oldAlbumId = loadAlbumId(albumIdPath);
        if (createNew || oldAlbumId == null) {
            if (oldAlbumId != null) {
                log.info("Album ID found, but createNew is true. Deleting old album.");
                deleteAlbum(oldAlbumId);
            }
            String albumName = UUID.randomUUID().toString();
            albumId = createAlbum(albumName);
            if (albumId.equals("false")) {
                log.severe("Failed to create album.");
                throw new RuntimeException("Failed to create album");
            }
            log.info("album criado 100% " + this.albumId);
            saveAlbumId(albumIdPath, albumId);
        } else {
            this.albumId = loadAlbumId(albumIdPath);
            if (this.albumId == null) {
                log.info("Album ID not found.");
            }
        }
        //log.info("JavaImgur initialized with album name: " + albumName);
    }

    public void setUsers(Users users) {
        this.users = users;
    }

    private void saveAlbumId(String albumIdPath, String albumId) {
        try (FileWriter writer = new FileWriter(albumIdPath)) {
            writer.write(albumId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save album ID", e);
        }
    }

    private String loadAlbumId(String albumIdPath) {
        try {
            return Files.readString(Paths.get(albumIdPath)).trim();
        } catch (IOException e) {
            return null;
        }
    }

    public String getAlbumId() {
        return albumId;
    }

    @Override
    public Result<String> createImage(String userId, byte[] imageContents, String password) {
        log.info("createImage(uid -> %s, content _, pwd -> %s)\n".formatted(userId, password));
        if (imageContents == null || imageContents.length == 0)
            return error(BAD_REQUEST);
        var uRes = users.getUser(userId, password);
        if (!uRes.isOK())
            return error(uRes.error());
        log.info("User %s authenticated successfully".formatted(userId));

        String imageId = uploadImage(userId, imageContents);
        log.info("Image uploaded with ID: " + imageId + "\n");
        Result<Boolean> result = addImageToAlbum(imageId);
        log.info("Image added to album: " + albumId + "\n");
        String uri = String.format("https://api.imgur.com/3/album/%s/image/%s", albumId, imageId);
        return ok(uri);
    }

    @Override
    public Result<byte[]> getImage(String userId, String imageId) {
        log.info("getImage(uid -> %s, iid -> %s)".formatted(userId, imageId));
        if (userId == null || imageId == null)
            return error(BAD_REQUEST);
        log.info("getting image with ID: " + imageId + " from album: " + albumId);
        String requestURL = String.format("https://api.imgur.com/3/album/%s/image/%s", albumId, imageId);
        return requestImage(requestURL);
    }

    @Override
    public Result<Void> deleteImage(String userId, String imageId, String password) {
        log.info("deleteImage(uid -> %s, iid -> %s, pwd -> %s)".formatted(userId, imageId, password));
        if (userId == null || imageId == null || password == null)
            return error(BAD_REQUEST);

        var uRes = users.getUser(userId, password);
        if (!uRes.isOK())
            return error(uRes.error());

        String requestURL = "https://api.imgur.com/3/image/" + imageId;
        return deleteRequest(requestURL, imageId);
    }

    @Override
    public Result<Void> deleteImageUponUserOrPostRemoval(String userId, String imageId) {
        return null;
    }

    @Override
    public Result<Void> teardown() {
        return null;
    }

    private boolean hasAlbum(String albumId) {
        // Check if the album exists in Imgur
        return false; // Placeholder implementation
    }

    private String createAlbum(String albumName) {
        OAuthRequest request = new OAuthRequest(Verb.POST, CREATE_ALBUM_URL);

        request.addHeader(CONTENT_TYPE_HDR, JSON_CONTENT_TYPE);
        request.setPayload(json.toJson(new impl.imgur.data.CreateAlbumArguments(albumName, albumName)));

        service.signRequest(accessToken, request);

        try {
            Response r = service.execute(request);

            if(r.getCode() != HTTP_SUCCESS) {
                //Operation failed
                log.severe("Operation Failed\nStatus: " + r.getCode() + "\nBody: " + r.getBody());
                return "false";
            } else {
                impl.imgur.data.BasicResponse body = json.fromJson(r.getBody(), impl.imgur.data.BasicResponse.class);
                log.info("Contents of Body: " + r.getBody());
                log.info("Operation Succedded\nAlbum name: " + albumName + "\nAlbum ID: " + body.getData().get("id"));
                this.albumId = body.getData().get("id").toString();
                return body.getData().get("id").toString();
            }

        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return "false";
    }

    private Result<Void> deleteAlbum(String albumId) {
        deleteImagesInAlbum(albumId);
        String requestURL = "https://api.imgur.com/3/album/" + albumId;

        OAuthRequest request = new OAuthRequest(Verb.DELETE, requestURL);

        service.signRequest(accessToken, request);

        try {
            Response r = service.execute(request);

            if (r.getCode() != HTTP_SUCCESS) {
                log.severe("Failed to delete album. Status: %d, Body: %s".formatted(r.getCode(), r.getBody()));
                return error(Result.ErrorCode.NOT_FOUND);
            }

            log.info("Album deleted successfully: " + albumId);
            return ok();
        } catch (InterruptedException | ExecutionException | IOException e) {
            log.severe("Error deleting album: " + e.getMessage());
            return error(Result.ErrorCode.INTERNAL_ERROR);
        }
    }

    private void deleteImagesInAlbum(String albumId) {
        String requestURL = "https://api.imgur.com/3/album/" + albumId;

        OAuthRequest request = new OAuthRequest(Verb.GET, requestURL);

        service.signRequest(accessToken, request);

        try {
            Response r = service.execute(request);

            if (r.getCode() != HTTP_SUCCESS) {
                log.severe("Failed to fetch images in album. Status: %d, Body: %s".formatted(r.getCode(), r.getBody()));
                return;
            }

            impl.imgur.data.BasicResponse body = json.fromJson(r.getBody(), impl.imgur.data.BasicResponse.class);
            var images = body.getData().get("images");
            if (images instanceof List<?>) {
                for (Object image : (List<?>) images) {
                    if (image instanceof Map<?, ?>) {
                        String imageId = ((Map<?, ?>) image).get("id").toString();
                        log.info("Deleting image with ID: " + imageId);
                        deleteRequest("https://api.imgur.com/3/image/" + imageId, imageId);
                    }
                }
            } else {
                log.warning("Unexpected data format for images in album: " + images);
            }
            log.info("All images in old album deleted successfully.");

        } catch (InterruptedException | ExecutionException | IOException e) {
            log.severe("Error fetching images in album: " + e.getMessage());
        }
    }

    private String uploadImage(String imageTitle, byte[] data) {
        OAuthRequest request = new OAuthRequest(Verb.POST, UPLOAD_IMAGE_URL);

        request.addHeader(CONTENT_TYPE_HDR, JSON_CONTENT_TYPE);
        request.setPayload(json.toJson(new impl.imgur.data.ImageUploadArguments(data, imageTitle)));

        service.signRequest(accessToken, request);

        try {
            Response r = service.execute(request);

            if(r.getCode() != HTTP_SUCCESS) {
                //Operation failed
                log.severe("Operation Failed\nStatus: " + r.getCode() + "\nBody: " + r.getBody());
                //return false;
            } else {
                impl.imgur.data.BasicResponse body = json.fromJson(r.getBody(), impl.imgur.data.BasicResponse.class);
                log.info("Operation Succedded\nImage title: " + imageTitle + "\nImage ID: " + body.getData().get("id") + "\"n");
                return body.getData().get("id").toString();
            }

        } catch (InterruptedException e) {
            log.severe("interrupted\n");
        } catch (ExecutionException e) {
            log.severe("execution\n");
        } catch (IOException e) {
            log.severe("IO\n");
            e.printStackTrace();
        }

        return "";
    }

    private Result<Boolean> addImageToAlbum(String imageId) {
        String requestURL = ADD_IMAGE_TO_ALBUM_URL.replaceAll("\\{\\{albumHash\\}\\}", albumId);

        OAuthRequest request = new OAuthRequest(Verb.POST, requestURL);

        request.addHeader(CONTENT_TYPE_HDR, JSON_CONTENT_TYPE);
        request.setPayload(json.toJson(new impl.imgur.data.AddImagesToAlbumArguments(imageId)));

        service.signRequest(accessToken, request);

        try {
            Response r = service.execute(request);


            if (r.getCode() != HTTP_SUCCESS) {
                //Operation failed
                log.info("ardeu");
                log.severe("Operation Failed\nStatus: " + r.getCode() + "\nBody: " + r.getBody());

                return error(Result.ErrorCode.INTERNAL_ERROR);
            } else {
                log.info("Contents of Body: " + r.getBody());
                impl.imgur.data.BooleanBasicResponse body = json.fromJson(r.getBody(), impl.imgur.data.BooleanBasicResponse.class);
                log.info("Operation Succedded");
                return ok(body.getData());
            }

        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return error(Result.ErrorCode.INTERNAL_ERROR);
    }

    private Result<byte[]> requestImage(String requestURL) {
        OAuthRequest request = new OAuthRequest(Verb.GET, requestURL);

        service.signRequest(accessToken, request);

        try {
            Response r = service.execute(request);

            if (r.getCode() != HTTP_SUCCESS) {
                log.severe("Failed to fetch image. Status: %d, Body: %s".formatted(r.getCode(), r.getBody()));
                return error(Result.ErrorCode.NOT_FOUND);
            }

            impl.imgur.data.BasicResponse body = json.fromJson(r.getBody(), impl.imgur.data.BasicResponse.class);
            log.info("Image fetched successfully: " + body.getData().get("id"));
            //byte[] imageData = java.util.Base64.getDecoder().decode(body.getData().get("link").toString());
            byte[] imageData = downloadImageAsByteArray(body.getData().get("link").toString());


            return ok(imageData);
        } catch (InterruptedException | ExecutionException | IOException e) {
            log.severe("Error fetching image: " + e.getMessage());
            return error(Result.ErrorCode.INTERNAL_ERROR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] downloadImageAsByteArray(String imageUrl) throws Exception {
        URL url = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }

            return baos.toByteArray();
        }
    }

    private Result<Void> deleteRequest(String requestURL, String imageId) {
        OAuthRequest request = new OAuthRequest(Verb.DELETE, requestURL);

        service.signRequest(accessToken, request);

        try {
            Response r = service.execute(request);

            if (r.getCode() != HTTP_SUCCESS) {
                log.severe("Failed to delete image. Status: %d, Body: %s".formatted(r.getCode(), r.getBody()));
                return error(Result.ErrorCode.NOT_FOUND);
            }

            log.info("Image deleted successfully: " + imageId);
            return ok();
        } catch (InterruptedException | ExecutionException | IOException e) {
            log.severe("Error deleting image: " + e.getMessage());
            return error(Result.ErrorCode.INTERNAL_ERROR);
        }
    }

}
