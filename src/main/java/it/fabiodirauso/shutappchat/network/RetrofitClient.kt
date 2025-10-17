package it.fabiodirauso.shutappchat.network

import android.content.Context
import it.fabiodirauso.shutappchat.api.*
import it.fabiodirauso.shutappchat.auth.AuthInterceptor
import it.fabiodirauso.shutappchat.config.ServerConfig
import it.fabiodirauso.shutappchat.config.AppConfigManager
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

interface ApiService {
    // Auth
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("auth/refresh")
    suspend fun refreshToken(): Response<LoginResponse>

    // User search and profiles
    @GET("users")
    suspend fun searchUsers(@Query("q") query: String, @Query("limit") limit: Int = 20): Response<UserSearchResponse>

    @GET("users/{username}")
    suspend fun getUserProfile(@Path("username") username: String): Response<UserProfileResponse>

    @GET("profile/me")
    suspend fun getMyProfile(): Response<UserProfileResponse>

    @POST("profile/update_user_info")
    suspend fun updateUserInfo(@Body request: UpdateUserInfoRequest): Response<BasicResponse>

    @POST("profile/update_profile_picture")
    suspend fun updateProfilePicture(@Body request: UpdateProfilePictureRequest): Response<BasicResponse>

    // Contacts management
    @GET("contacts")
    suspend fun getContacts(): Response<ContactsResponse>

    @GET("contacts/status")
    suspend fun getContactStatus(@Query("username") username: String): Response<ContactStatusResponse>

    @GET("contacts/requests")
    suspend fun getContactRequests(@Query("type") type: String = "incoming"): Response<ContactRequestsResponse>

    @POST("contacts/request")
    suspend fun sendContactRequest(@Body request: ContactRequestRequest): Response<ContactRequestResponse>

    @POST("contacts/respond")
    suspend fun respondToContactRequest(@Body request: ContactRespondRequest): Response<ContactRespondResponse>

    @DELETE("contacts/{username}")
    suspend fun removeContact(@Path("username") username: String): Response<ContactRequestResponse>

    // Media management
    @POST("media")
    suspend fun initMediaUpload(@Body request: MediaInitRequest): Response<MediaInitResponse>

    @PUT("media/data")
    suspend fun uploadMediaData(@Query("id") id: String, @Query("offset") offset: Long = 0, @Body data: RequestBody): Response<MediaUploadResponse>

    @GET("media/data")
    suspend fun downloadMediaData(@Query("id") id: String): Response<ResponseBody>

    // Messages
    @POST("messages")
    suspend fun sendMessage(@Body request: MessageRequest): Response<MessageResponse>

    @GET("pendings")
    suspend fun getPendingMessages(): Response<Map<String, Any>>

    @DELETE("pendings/{id}")
    suspend fun deletePendingMessage(@Path("id") id: Int): Response<BasicResponse>
    
    // User info (for ID lookup)
    @GET("users/{username}")
    suspend fun getUser(@Path("username") username: String): Response<Map<String, Any>>

    // Legacy media upload (keeping for compatibility)
    @Multipart
    @POST("upload.php")
    suspend fun uploadMedia(@Part file: MultipartBody.Part): Response<ResponseBody>
    
    // Upload client logs (ZIP file)
    @Multipart
    @POST("logs/upload")
    suspend fun uploadLogs(@Part file: MultipartBody.Part): Response<com.google.gson.JsonObject>
    
    // ===== GROUPS API =====
    
    // List groups for current user
    @GET("groups")
    suspend fun getGroups(): Response<GroupsListResponse>
    
    // Create new group
    @POST("groups/create")
    suspend fun createGroup(@Body request: CreateGroupRequest): Response<CreateGroupResponse>
    
    // Add members to group
    @POST("groups/{groupId}/members/add")
    suspend fun addGroupMembers(
        @Path("groupId") groupId: String,
        @Body request: AddMembersRequest
    ): Response<BasicResponse>
    
    // Remove member from group
    @DELETE("groups/{groupId}/members/{userId}")
    suspend fun removeGroupMember(
        @Path("groupId") groupId: String,
        @Path("userId") userId: Long
    ): Response<BasicResponse>
    
    // Update member role
    @PUT("groups/{groupId}/members/{userId}/role")
    suspend fun updateMemberRole(
        @Path("groupId") groupId: String,
        @Path("userId") userId: Long,
        @Body request: UpdateRoleRequest
    ): Response<BasicResponse>
    
    // Update group settings
    @PUT("groups/{groupId}/settings")
    suspend fun updateGroupSettings(
        @Path("groupId") groupId: String,
        @Body request: UpdateGroupSettingsRequest
    ): Response<BasicResponse>
    
    // Delete/archive group (admin only - removes for all members)
    @DELETE("groups/{groupId}")
    suspend fun deleteGroup(@Path("groupId") groupId: String): Response<BasicResponse>
    
    // Leave group (user - removes only from this device)
    @POST("groups/{groupId}/leave")
    suspend fun leaveGroup(@Path("groupId") groupId: String): Response<BasicResponse>
    
    // Get group members list
    @GET("groups/{groupId}/members")
    suspend fun getGroupMembers(@Path("groupId") groupId: String): Response<GroupMembersResponse>
    
    // Get group info
    @GET("groups/{groupId}/info")
    suspend fun getGroupInfo(@Path("groupId") groupId: String): Response<GroupInfoResponse>
    
    // Security/Privacy settings
    @GET("security/settings")
    suspend fun getSecuritySettings(): Response<SecuritySettingsResponse>
    
    @POST("security/settings")
    suspend fun updateSecuritySettings(@Body request: SecurityUpdateRequest): Response<SecurityUpdateResponse>
    
    // App configuration
    @GET("app_links.php")
    suspend fun getAppConfig(): Response<AppConfigResponse>
}

object RetrofitClient {
    private const val BASE_URL = ServerConfig.API_BASE_URL

    // User-Agent provider - set from Application context
    @Volatile
    private var userAgent: String = "ShutAppChat|v1.0|1" // Default fallback
    
    @Volatile
    private var appContext: Context? = null

    fun setUserAgent(ua: String) {
        userAgent = ua
    }
    
    fun initialize(context: Context) {
        appContext = context.applicationContext
        refreshClient() // Ricrea i client con il nuovo context
    }

    @Volatile
    private var currentToken: String? = null

    fun setAuthToken(token: String?) {
        currentToken = token
    }

    private val okHttpClient: OkHttpClient
        get() {
            val context = appContext ?: throw IllegalStateException("RetrofitClient not initialized. Call initialize(context) first.")
            
            // HTTP logging interceptor per debug
            val loggingInterceptor = okhttp3.logging.HttpLoggingInterceptor { message ->
                android.util.Log.d("HTTP", message)
            }.apply {
                level = okhttp3.logging.HttpLoggingInterceptor.Level.BODY
            }
            
            return OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)  // Aggiunto logging
                .addInterceptor(AuthInterceptor(context) { userAgent })
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)  // 30s per connessione
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)    // 2 minuti per lettura (file grandi)
                .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)   // 2 minuti per scrittura (upload)
                .retryOnConnectionFailure(true)  // Riprova automaticamente in caso di errore
                .build()
        }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val apiService: ApiService by lazy { retrofit.create(ApiService::class.java) }
    
    // Go WebSocket service for avatar thumbnails and other helpers
    private val wsRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://shutappchat.fabiodirauso.it/") // Base URL for WebSocket service
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
        
    val wsApiService: WebSocketApiService by lazy { wsRetrofit.create(WebSocketApiService::class.java) }
    
    // ===== CONFIGURAZIONE DINAMICA =====
    
    @Volatile
    private var dynamicRetrofit: Retrofit? = null
    
    @Volatile
    private var dynamicWsRetrofit: Retrofit? = null

    /**
     * Ottiene l'istanza ApiService usando la configurazione dinamica
     */
    fun getApiService(configManager: AppConfigManager): ApiService {
        if (dynamicRetrofit == null) {
            synchronized(this) {
                if (dynamicRetrofit == null) {
                    dynamicRetrofit = Retrofit.Builder()
                        .baseUrl(configManager.apiBaseUrl)
                        .client(okHttpClient)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                }
            }
        }
        return dynamicRetrofit!!.create(ApiService::class.java)
    }
    
    /**
     * Forza la ricreazione del client con nuova configurazione
     */
    fun refreshClient() {
        synchronized(this) {
            dynamicRetrofit = null
            dynamicWsRetrofit = null
        }
    }
        
    fun getWsApiService(configManager: AppConfigManager): WebSocketApiService {
        if (dynamicWsRetrofit == null) {
            synchronized(this) {
                if (dynamicWsRetrofit == null) {
                    // Estrae l'URL base dal WebSocket URL (rimuove il path /ws)
                    val baseUrl = configManager.wsUrl.replace("wss://", "https://").replace("/ws", "/")
                    dynamicWsRetrofit = Retrofit.Builder()
                        .baseUrl(baseUrl)
                        .client(okHttpClient)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                }
            }
        }
        return dynamicWsRetrofit!!.create(WebSocketApiService::class.java)
    }
}

// Separate interface for WebSocket service endpoints
interface WebSocketApiService {
    @GET("api/media/avatar")
    suspend fun getAvatarThumbnail(
        @Query("id") id: String,
        @Query("user") user: String,
        @Query("token") token: String
    ): Response<ResponseBody>
    
    @GET("api/pendings")
    suspend fun getPendingsWs(
        @Query("user") user: String,
        @Query("token") token: String
    ): Response<PendingMessagesWsResponse>
}