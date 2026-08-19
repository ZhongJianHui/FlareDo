package dev.dimension.flare.data.network.discourse

import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.Field
import de.jensklingenberg.ktorfit.http.FormUrlEncoded
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Header
import de.jensklingenberg.ktorfit.http.Multipart
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.PUT
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Query
import de.jensklingenberg.ktorfit.http.Url
import dev.dimension.flare.data.network.discourse.model.DiscourseActionResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseBookmarkResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCategoryListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCsrfResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCurrentSessionResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseNotificationResponse
import dev.dimension.flare.data.network.discourse.model.DiscoursePostMutationResponse
import dev.dimension.flare.data.network.discourse.model.DiscoursePostStream
import dev.dimension.flare.data.network.discourse.model.DiscourseSearchResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseSiteResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTagsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicDetail
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUploadResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserActionsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserBookmarkListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummaryResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent

/** Raw Ktorfit routes. Only [DefaultDiscourseApi] may expose these outside the transport package. */
internal interface DiscourseWireApi {
    @GET("site.json")
    suspend fun site(): DiscourseSiteResponse

    @GET("categories.json")
    suspend fun categories(): DiscourseCategoryListResponse

    @GET("tags.json")
    suspend fun tags(): DiscourseTagsResponse

    @GET
    suspend fun topics(
        @Url url: String,
    ): DiscourseTopicListResponse

    @GET("t/{topicId}.json")
    suspend fun topic(
        @Path("topicId") topicId: Long,
        @Query("track_visit") trackVisit: Boolean? = null,
        @Header("Discourse-Track-View") trackView: String? = null,
        @Header("Discourse-Track-View-Topic-Id") trackedTopicId: String? = null,
    ): DiscourseTopicDetail

    @GET("t/{topicId}/posts.json")
    suspend fun topicPosts(
        @Path("topicId") topicId: Long,
        @Query("post_ids[]") postIds: List<Long>,
        @Query("include_suggested") includeSuggested: Boolean? = null,
    ): DiscoursePostStream

    @GET("search.json")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int? = null,
        @Query("type_filter") type: String? = null,
    ): DiscourseSearchResponse

    @GET("u/{username}.json")
    suspend fun user(
        @Path("username") username: String,
    ): DiscourseUserResponse

    @GET("u/{username}/summary.json")
    suspend fun userSummary(
        @Path("username") username: String,
    ): DiscourseUserSummaryResponse

    @GET("user_actions.json")
    suspend fun userActions(
        @Query("username") username: String,
        @Query("offset") offset: Int,
        @Query("filter") filter: String? = null,
    ): DiscourseUserActionsResponse

    @GET("notifications")
    suspend fun notifications(
        @Query("offset") offset: Int? = null,
        @Query("limit") limit: Int,
    ): DiscourseNotificationResponse

    @GET("session/current.json")
    suspend fun currentSession(): DiscourseCurrentSessionResponse

    @GET("session/csrf")
    suspend fun csrf(): DiscourseCsrfResponse

    @GET("u/{username}/bookmarks.json")
    suspend fun userBookmarks(
        @Path("username") username: String,
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int,
    ): DiscourseUserBookmarkListResponse

    @GET("bookmarks.json")
    suspend fun bookmarkedTopics(
        @Query("page") page: Int? = null,
    ): DiscourseTopicListResponse

    @POST("posts.json")
    @FormUrlEncoded
    suspend fun createPost(
        @Header("X-CSRF-Token") csrfToken: String,
        @Field("raw") raw: String,
        @Field("title") title: String? = null,
        @Field("topic_id") topicId: Long? = null,
        @Field("category") categoryId: Long? = null,
        @Field("archetype") archetype: String? = null,
        @Field("reply_to_post_number") replyToPostNumber: Int? = null,
        @Field("tags[]") tags: List<String> = emptyList(),
    ): DiscoursePostMutationResponse

    @PUT("posts/{postId}.json")
    @FormUrlEncoded
    suspend fun updatePost(
        @Path("postId") postId: Long,
        @Header("X-CSRF-Token") csrfToken: String,
        @Field("post[raw]") raw: String,
        @Field("post[edit_reason]") editReason: String? = null,
    ): DiscoursePostMutationResponse

    @PUT("notifications/mark-read")
    @FormUrlEncoded
    suspend fun markNotificationsRead(
        @Header("X-CSRF-Token") csrfToken: String,
        @Field("id") notificationId: Long? = null,
    ): Unit

    @POST("post_actions")
    @FormUrlEncoded
    suspend fun createPostAction(
        @Header("X-CSRF-Token") csrfToken: String,
        @Field("id") postId: Long,
        @Field("post_action_type_id") actionTypeId: Long,
        @Field("flag_topic") flagTopic: Boolean,
        @Field("message") message: String? = null,
    ): DiscourseActionResponse

    @DELETE("post_actions/{postId}")
    suspend fun deletePostAction(
        @Path("postId") postId: Long,
        @Header("X-CSRF-Token") csrfToken: String,
        @Query("post_action_type_id") actionTypeId: Long,
    ): DiscourseActionResponse

    @POST("bookmarks.json")
    @FormUrlEncoded
    suspend fun createBookmark(
        @Header("X-CSRF-Token") csrfToken: String,
        @Field("bookmarkable_id") bookmarkableId: Long,
        @Field("bookmarkable_type") bookmarkableType: String,
        @Field("name") name: String? = null,
        @Field("reminder_at") reminderAt: String? = null,
        @Field("auto_delete_preference") autoDeletePreference: Int? = null,
    ): DiscourseBookmarkResponse

    @DELETE("bookmarks/{bookmarkId}.json")
    suspend fun deleteBookmark(
        @Path("bookmarkId") bookmarkId: Long,
        @Header("X-CSRF-Token") csrfToken: String,
    ): Unit

    @Multipart
    @POST("uploads.json")
    suspend fun upload(
        @Header("X-CSRF-Token") csrfToken: String,
        @Query("client_id") clientId: String? = null,
        @Body body: MultiPartFormDataContent,
    ): DiscourseUploadResponse
}

/** Creates generated Ktorfit routes around the already hardened [client]. */
internal fun createDiscourseWireTransport(client: HttpClient): DiscourseWireApi =
    de.jensklingenberg.ktorfit
        .ktorfit {
            baseUrl("$DISCOURSE_ORIGIN/")
            httpClient(client)
            converterFactories(ResponseConverterFactory())
        }.createDiscourseWireApi()
