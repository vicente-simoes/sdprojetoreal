package api.rest;

import java.util.List;

import api.Post;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path(RestContent.PATH)
public interface RestContent {

	public final static String HEADER_VERSION = "X-FCTREDDIT-VERSION";

	String PATH = "/posts";
	String PASSWORD = "pwd";
	String POSTID = "postId";
	String TIMESTAMP = "timestamp";
	String REPLIES = "replies";
	String UPVOTE = "upvote";
	String DOWNVOTE = "downvote";
	String USERID = "userId";
	String SORTBY = "sortBy";
	String TIMEOUT = "timeout";

	String FORGET = "forget";
	String MEDIA_URL = "mediaUrl";
	
	/**
	 * The following constants are the values that can be sent for the query parameter SORTBY
	 **/
    String MOST_UP_VOTES = "votes";
	String MOST_REPLIES = "replies";
	
	
	/**
	 * Creates a new Post (that can be an answer to another Post), generating its unique identifier. 
	 * The result should be the identifier of the Post in case of success.
	 * The creation timestamp of the post should be set to be the time in the server when the request
	 * was received.
	 * 
	 * @param post - The Post to be created, that should contain the userId of the author in the appropriate field.
	 * @param userPassword - the password of author of the new post
	 * @return OK and PostID if the post was created;
	 * NOT FOUND, if the owner of the short does not exist;
	 * FORBIDDEN, if the password is not correct;
	 * BAD_REQUEST, otherwise.
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	Response createPost(@HeaderParam(HEADER_VERSION)long version, Post post, @QueryParam(PASSWORD) String userPassword);
	
	/**
	 * Retrieves a list with all top-level Posts unique identifiers (i.e., Posts that have no parent Post).
	 * By default (i.e., when no query parameter is passed) all top-level posts should be returned in the 
	 * order in which they were created. The effects of both optional parameters can be combined to affect
	 * the answer.
	 * 
	 * @param timestamp this is an optional parameter, if it is defined then the returned list
	 * should only contain Posts whose creation timestamp is equal or above the provided timestamp.
	 * @param sortOrder this is an optional parameter, the admissible values are on constants MOST_UP_VOTES
	 * and MOST_REPLIES, if the first is indicated, posts IDs should be ordered from the Post with more votes
	 * to the one with less votes. If the second is provided posts IDs should be ordered from the Post with 
	 * more replies to the one with less replies.
	 * @return 	OK and the List of PostIds that match all options in the right order 
	 * 			
	 */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
    Response getPosts(@HeaderParam(HEADER_VERSION)long version, @QueryParam(TIMESTAMP) long timestamp, @QueryParam(SORTBY) String sortOrder);
	
	/**
	 * Retrieves a given post.
	 * 
	 * @param postId the unique identifier of the short to be retrieved
	 * @return 	OK and the Post in case of success 
	 * 			NOT_FOUND if postId does not match an existing Post
	 */
	@GET
	@Path("{" + POSTID + "}")
	@Produces(MediaType.APPLICATION_JSON)
	Response getPost(@HeaderParam(HEADER_VERSION)long version, @PathParam(POSTID) String postId);

	/**
	 * Retrieves a list with all unique identifiers of posts that have the post
	 * identified by the postId as their parent (i.e., the replies to that post),
	 * the order should be the creation order of those posts.
	 * @param postId the postId for which answers want to be obtained
	 * @param timeout (optional) indicates the maximum amount of time that this operation should
	 * 		  wait (before returning a reply to the client) for a new answer to be added
	 * 		  to the post. If a new answer is added to the target post after the start of
	 * 		  the execution of this operation and before the timeout expires an answer should
	 * 		  be sent to the client at that time.
	 * @return 	OK and the List of PostIds that are answers to the post ordered by creationTime
	 * 			NOT_FOUND if postId does not match an existing Post
	 */
	@GET
	@Path("{" + POSTID + "}/" + REPLIES)
	@Produces(MediaType.APPLICATION_JSON)
	public Response getPostAnswers(@HeaderParam(HEADER_VERSION)long version, @PathParam(POSTID) String postId, @QueryParam(TIMEOUT) long timeout);
	
	/**
	 * Updates the contents of a post restricted to the fields:
	 * - content
	 * - mediaUrl
	 * @param postId the post that should be updated
	 * @param userPassword the password, it is assumed that only the author of the post 
	 * can updated it, and as such, the password sent in the operation should belong to 
	 * that user.
	 * @param post A post object with the fields to be updated
	 * @return 	OK the updated post, in case of success.
	 * 			FORBIDDEN, if the password is not correct;
	 * 			BAD_REQUEST, otherwise.
	 */
	@PUT
	@Path("{" + POSTID + "}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	Response updatePost(@HeaderParam(HEADER_VERSION)long version, @PathParam(POSTID) String postId, @QueryParam(PASSWORD) String userPassword, Post post);
	
	/**
	 * Deletes a given Post, only the author of the Post can do this operation. A successful delete will also remove
	 * any reply to this post (or replies to those replies) even if performed by different authors.
	 * 
	 * @param postId the unique identifier of the Post to be deleted
	 * @return 	NO_CONTENT in case of success 
	 * 			NOT_FOUND if postId does not match an existing post
	 * 			FORBIDDEN if the password is not correct (it should always be considered the authorId 
	 * 					  of the post as the user that is attempting to execute this operation);
	 */	
	@DELETE
	@Path("{" + POSTID + "}")
	Response deletePost(@HeaderParam(HEADER_VERSION)long version, @PathParam(POSTID) String postId, @QueryParam(PASSWORD) String userPassword);
	
	/**
	 * Adds an upvote to a given post made by a specific user (might be different from the author
	 * of the post). The vote must be authenticated by the password of the user adding the upvote. 
	 * The upvote on a post can be only be made once by an user, and the user must not have a downvote
	 * on that post.
	 * @param postId unique identifier of the post over which the upvote is made
	 * @param userId unique identifier of the user making the upvote
	 * @param userPassword Password of user making the upvote
	 * @return 	NO_CONTENT in case of success
	 * 			NOT_FOUND if the postId does not match an existing post or the user does not exists
	 * 			FORBIDDEN if the password is not correct
	 * 			CONFLICT if the user already has made an upvote or downvote on the post
	 *			BAD_REQUEST otherwise
	 */
	@POST
	@Path("{" + POSTID + "}/" + UPVOTE + "/{" + USERID + "}" )
	Response upVotePost(@HeaderParam(HEADER_VERSION)long version, @PathParam(POSTID) String postId, @PathParam(USERID) String userId, @QueryParam(PASSWORD) String userPassword);
	
	/**
	 * Removes a previously added upvote to a given post made by a specific user (might be different from the author
	 * of the post). The action must be authenticated by the password of the user removing the upvote.
	 * @param postId unique identifier of the post over which the upvote is removed
	 * @param userId unique identifier of the user removing the upvote
	 * @param userPassword Password of user removing the upvote
	 * @return 	NO_CONTENT in case of success
	 * 			NOT_FOUND if the postId does not match an existing post or the user does not exists
	 * 			FORBIDDEN if the password is not correct
	 * 			CONFLICT if the user had not made an upvote on this post previously
	 *			BAD_REQUEST otherwise
	 */
	@DELETE
	@Path("{" + POSTID + "}/" + UPVOTE + "/{" + USERID + "}" )
	Response removeUpVotePost(@HeaderParam(HEADER_VERSION)long version, @PathParam(POSTID) String postId, @PathParam(USERID) String userId, @QueryParam(PASSWORD) String userPassword);
	
	/**
	 * Adds an downvote to a given post made by a specific user (might be different from the author
	 * of the post). The vote must be authenticated by the password of the user adding the downvote. 
	 * The downvote on a post can be only be made once by an user, and the user must not have a upvote
	 * on that post.
	 * @param postId unique identifier of the post over which the downvote is made
	 * @param userId unique identifier of the user making the downvote
	 * @param userPassword Password of user making the downvote
	 * @return 	NO_CONTENT in case of success
	 * 			NOT_FOUND if the postId does not match an existing post or the user does not exists
	 * 			FORBIDDEN if the password is not correct
	 * 			CONFLICT if the user already has made an upvote or downvote on the post
	 *			BAD_REQUEST otherwise
	 */
	@POST
	@Path("{" + POSTID + "}/" + DOWNVOTE  + "/{" + USERID + "}" )
	Response downVotePost(@HeaderParam(HEADER_VERSION)long version, @PathParam(POSTID) String postId, @PathParam(USERID) String userId, @QueryParam(PASSWORD) String userPassword);
	
	/**
	 * Removes a previously added downvote to a given post made by a specific user (might be different from the author
	 * of the post). The action must be authenticated by the password of the user removing the downvote. 
	 * @param postId unique identifier of the post over which the downvote is removed
	 * @param userId unique identifier of the user removing the downvote
	 * @param userPassword Password of user removing the downvote
	 * @return 	NO_CONTENT in case of success
	 * 			NOT_FOUND if the postId does not match an existing post or the user does not exists
	 * 			FORBIDDEN if the password is not correct
	 * 			CONFLICT if the user had not made an downvote on this post previously
	 *			BAD_REQUEST otherwise
	 */
	@DELETE
	@Path("{" + POSTID + "}/" + DOWNVOTE + "/{" + USERID + "}" )
	Response removeDownVotePost(@HeaderParam(HEADER_VERSION)long version, @PathParam(POSTID) String postId, @PathParam(USERID) String userId, @QueryParam(PASSWORD) String userPassword);
	
	/**
	 * Exposes the number of upvotes currently associated with a given post
	 * @param postId the post that is targeted by this operation
	 * @return	OK and the number of upvotes in case of success
	 * 			NOT_FOUND if the postId does not match an existing post
	 */
	@GET
	@Path("{" + POSTID + "}/" + UPVOTE)
	Response getupVotes(@HeaderParam(HEADER_VERSION)long version, @PathParam(POSTID) String postId);
	
	/**
	 * Exposes the number of downvotes currently associated with a given post
	 * @param postId the post that is targeted by this operation
	 * @return	OK and the number of downvotes in case of success
	 * 			NOT_FOUND if the postId does not match an existing post
	 */
	@GET
	@Path("{" + POSTID + "}/" + DOWNVOTE)
	Response getDownVotes(@HeaderParam(HEADER_VERSION)long version, @PathParam(POSTID) String postId);

	/**
	 * Removes all information about a user.
	 * In particular, removes the authorship of all posts published by the user
	 * @param uid Identifier of the forgotten user. No need to check if it exists.
	 */
	@DELETE
	@Path(FORGET + "/{" + USERID + "}")
	Response forgetUser(@HeaderParam(HEADER_VERSION)long version, @PathParam(USERID) String uid);

	/**
	 * Retrieves a list of post identifiers that have the provided mediaUrl as their mediaUrl.
	 * The mediaUrl is the URL of an image or video that is associated with a post.
	 *
	 * @param mediaUrl the URL of the media to search for
	 * @return OK and a list of post identifiers that have the specified mediaUrl
	 */
	@GET
	@Path("imageUrl/{" + MEDIA_URL + "}")
	@Produces(MediaType.APPLICATION_JSON)
	Response getPostsByImage(@HeaderParam(HEADER_VERSION)long version, @PathParam(MEDIA_URL) String mediaUrl);

}
