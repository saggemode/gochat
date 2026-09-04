# GoChat: Comprehensive Flutter to Native Kotlin Migration Guide

This document provides an exhaustive, production-grade architectural and implementation blueprint for recreating the entire GoChat Flutter application into **Native Android Kotlin** inside the `GoChat_Kotlin` project.

---

## 1. Technology Stack Translation

| Feature / Layer | Flutter Implementation | Native Android Kotlin Target |
| :--- | :--- | :--- |
| **Language & Runtime** | Dart 3.13+ | **Kotlin 2.0+** (JVM 17 / 21) |
| **UI Framework** | Flutter Widgets / Material 3 | **Jetpack Compose** (or Material 3 XML Views + ViewBinding) |
| **Architecture** | Provider (`AppState` ChangeNotifier) | **MVVM + Clean Architecture** (Coroutines + `StateFlow` / `SharedFlow`) |
| **Dependency Injection**| Manual / Constructor Injection | **Hilt (Dagger)** or **Koin** |
| **REST Networking** | `http` package + JSON serialization | **Retrofit 2** + **OkHttp 4** + **Kotlinx.Serialization** |
| **Real-time WebSocket**| `web_socket_channel` | **OkHttp WebSocket** + Coroutines Flow / Channels |
| **Local Database** | `sqflite` (SQLite) | **Room Database** (`@Entity`, `@Dao`, `@TypeConverter`) |
| **Key-Value Storage** | `shared_preferences` & `flutter_secure_storage` | **EncryptedSharedPreferences** or **Jetpack DataStore** |
| **Audio Recording** | `record` package | **Android MediaRecorder** / `AudioRecord` |
| **Audio/Video Playback**| `audioplayers` / custom media player | **AndroidX Media3 (ExoPlayer)** |
| **Image Loading** | `cached_network_image` / `MediaImageHelper` | **Coil** (supports URLs, Base64 Data URIs, local cache) |
| **VoIP / WebRTC** | `flutter_webrtc` | **WebRTC Android SDK** (`google-webrtc`) + **TelecomManager** |
| **Contact Sync** | `flutter_contacts` | Android **ContactsContract** ContentResolver |
| **Haptic Feedback** | `HapticFeedback` | **Vibrator** / `CombinedVibration` (`VibrationEffect`) |

---

## 2. Recommended `GoChat_Kotlin/app/build.gradle.kts` Dependencies

Add the following modern Android libraries to `GoChat_Kotlin/app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
}

android {
    namespace = "com.example.gochat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gochat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        compose = true // Optional if using Jetpack Compose
    }
}

dependencies {
    // AndroidX & Architecture
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.0")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Networking (Retrofit & OkHttp with WebSocket support)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Security & Storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Media & Image Loading (Coil handles Base64 & Network)
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // QR Code Scanning (Zebra Crossing / CameraX)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
}
```

---

## 3. Data Models Mapping (`flutter/lib/core/models/` -> Kotlin `data class`)

### 3.1. User Model (`user.dart` -> `User.kt`)
```kotlin
package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val phone: String = "",
    val pin: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    val bio: String = "Hey there! I am using GoChat.",
    val status: String = "offline",
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null
)
```

### 3.2. Message Model (`message.dart` -> `Message.kt`)
```kotlin
package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MessageType { TEXT, IMAGE, VIDEO, AUDIO, VOICE, FILE, LOCATION, CONTACT, POLL, STICKER, PRODUCT, PING }
enum class MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED }

@Serializable
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("sender_name") val senderName: String = "",
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.SENT,
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("media_thumbnail") val mediaThumbnail: String? = null,
    @SerialName("media_duration") val mediaDuration: Int? = null,
    @SerialName("media_size") val mediaSize: Long? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("reply_to_text") val replyToText: String? = null,
    @SerialName("reply_to_sender_name") val replyToSenderName: String? = null,
    @SerialName("is_view_once") val isViewOnce: Boolean = false,
    @SerialName("is_ping") val isPing: Boolean = false,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
    val isMe: Boolean = false
)
```

### 3.3. Conversation Model (`conversation.dart` -> `Conversation.kt`)
```kotlin
package com.example.gochat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ConversationType { DIRECT, GROUP, CHANNEL }
enum class InvitationStatus { NONE, PENDING_INCOMING, PENDING_OUTGOING, ACCEPTED, DECLINED }

@Serializable
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String,
    val title: String,
    @SerialName("avatar_url") val avatarUrl: String = "",
    val type: ConversationType = ConversationType.DIRECT,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("is_muted") val isMuted: Boolean = false,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("partner_pin") val partnerPin: String? = null,
    @SerialName("invitation_status") val invitationStatus: InvitationStatus = InvitationStatus.NONE,
    @SerialName("member_ids") val memberIds: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long = System.currentTimeMillis()
)
```

### 3.4. Story & StoryViewer Models (`story.dart` -> `Story.kt`)
```kotlin
package com.example.gochat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StoryViewer(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("viewed_at") val viewedAt: String = ""
)

@Serializable
data class StoryItem(
    val id: String,
    @SerialName("media_url") val mediaUrl: String,
    val caption: String = "",
    @SerialName("media_type") val mediaType: String = "image", // "image", "video", "text"
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("view_count") val viewCount: Int = 0,
    val viewers: List<StoryViewer> = emptyList()
)

@Serializable
data class UserStories(
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val userName: String,
    @SerialName("user_avatar") val userAvatar: String = "",
    val stories: List<StoryItem> = emptyList(),
    val isMe: Boolean = false
) {
    val totalViewCount: Int get() = stories.sumOf { it.viewCount }
}
```

---

## 4. Services & Networking Layer

### 4.1. Retrofit API Service (`ApiService.kt`)
Matches all Go Gateway endpoints defined in `ApiConstants.dart`:

```kotlin
package com.example.gochat.data.api

import com.example.gochat.data.model.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface GoChatApiService {
    // Auth
    @POST("api/v1/auth/request-otp")
    suspend fun requestOtp(@Body body: Map<String, String>): Response<Map<String, Any>>

    @POST("api/v1/auth/verify-otp")
    suspend fun verifyOtp(@Body body: Map<String, String>): Response<Map<String, Any>>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: Map<String, String>): Response<Map<String, Any>>

    @GET("api/v1/auth/pin/{pin}")
    suspend fun lookupByPin(@Path("pin") pin: String): Response<User>

    // Conversations & Messages
    @GET("api/v1/conversations")
    suspend fun getConversations(): Response<List<Conversation>>

    @POST("api/v1/conversations")
    suspend fun createConversation(@Body body: Map<String, Any>): Response<Conversation>

    @GET("api/v1/conversations/{id}/messages")
    suspend fun getMessages(@Path("id") convId: String): Response<List<Message>>

    @POST("api/v1/conversations/{id}/messages")
    suspend fun sendMessage(
        @Path("id") convId: String,
        @Body body: Map<String, Any?>
    ): Response<Message>

    // Stories & Views
    @GET("api/v1/stories")
    suspend fun getStories(): Response<Map<String, List<UserStories>>>

    @POST("api/v1/stories")
    suspend fun postStory(@Body body: Map<String, String>): Response<StoryItem>

    @POST("api/v1/stories/{id}/view")
    suspend fun viewStory(@Path("id") storyId: String): Response<Unit>

    @GET("api/v1/stories/{id}/viewers")
    suspend fun getStoryViewers(@Path("id") storyId: String): Response<Map<String, List<StoryViewer>>>

    // Media Upload
    @Multipart
    @POST("api/v1/media/upload")
    suspend fun uploadMedia(@Part file: MultipartBody.Part): Response<Map<String, String>>
}
```

### 4.2. Realtime WebSocket Client (`GoChatWebSocket.kt`)
Replaces `WebSocketService.dart` using OkHttp:

```kotlin
package com.example.gochat.data.websocket

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import org.json.JSONObject

class GoChatWebSocket(private val client: OkHttpClient) {
    private var webSocket: WebSocket? = null
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun connect(token: String) {
        val request = Request.Builder()
            .url("wss://gochat-kvpj.onrender.com/ws?token=$token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    _events.tryEmit(json)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Reconnect logic with exponential backoff
            }
        })
    }

    fun send(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "User logged out")
        webSocket = null
    }
}
```

---

## 5. Room Database (`DatabaseService.dart` -> `AppDatabase.kt`)

```kotlin
package com.example.gochat.data.db

import androidx.room.*
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.Message

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): kotlinx.coroutines.flow.Flow<List<Conversation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(convs: List<Conversation>)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :convId")
    suspend fun markConversationAsRead(convId: String)

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    fun getMessagesForConversation(convId: String): kotlinx.coroutines.flow.Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)
}

@Database(entities = [Conversation::class, Message::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
```

---

## 6. Architecture & State Management (Translating `AppState.dart`)

Instead of storing all state inside a single monolithic 2,100-line `AppState` class, divide the logic into **Repository singletons** and dedicated **ViewModels**:

```
app/src/main/java/com/example/gochat/
├── data/
│   ├── api/            # Retrofit Service, AuthInterceptor
│   ├── db/             # Room Database, DAOs, Entities
│   ├── model/          # Data Classes (User, Message, Story, etc.)
│   ├── repository/     # AuthRepository, ChatRepository, StoryRepository
│   └── websocket/      # OkHttp WebSocket Handler
├── ui/
│   ├── auth/           # LoginActivity, OtpFragment, PinView
│   ├── chat/           # ChatListFragment, ChatRoomActivity, ChatBubbleAdapter
│   ├── stories/        # StoriesFragment, StoryViewerActivity, ViewersBottomSheet
│   ├── calls/          # CallsFragment, ActiveCallActivity
│   └── settings/       # SettingsFragment, ProfileActivity
└── util/               # AudioRecorder, CoilImageLoader, DateFormatter
```

### Key Business Logic Rules to Preserve:
1. **Active Conversation Tracking & Unread Counters**:
   - Track `activeConversationId` in `ChatRepository`.
   - When viewing `convId`, incoming WebSocket messages for `convId` are immediately marked as read and sent as read receipts (`read_receipt` event).
   - Tapping a conversation in `ChatListFragment` immediately executes `chatDao.markConversationAsRead(convId)`.
2. **Quoted Replies (Messages & Stories)**:
   - In `ChatRoomActivity`, when swiping to reply or replying to a story:
     - Set `replyToId`, `replyToText`, `replyToSenderName`, and `mediaThumbnail`.
     - In `ChatBubbleAdapter`, render the left accent border, bold sender name, and media preview thumbnail.
3. **Story View Tracking & Modal Sheet**:
   - In `StoryViewerActivity`, when viewing a contact's story, send `POST /api/v1/stories/{id}/view` and dispatch `story_viewed` WebSocket event.
   - For own stories, render `👁 N views` pill at the bottom. Tapping it pauses timer and shows `ViewersBottomSheet` populated via `getStoryViewers(storyId)`.

---

## 7. Migration Execution Roadmap

| Step | Goal | Files to Create / Configure |
| :--- | :--- | :--- |
| **Phase 1** | Project Setup & Gradle | Update `GoChat_Kotlin/app/build.gradle.kts` with Retrofit, Room, Coil, Coroutines. |
| **Phase 2** | Data Models & Room DB | Implement `Message`, `Conversation`, `StoryItem`, `User` models and `AppDatabase`. |
| **Phase 3** | Networking & WebSocket | Configure `GoChatApiService`, `AuthInterceptor`, and `GoChatWebSocket`. |
| **Phase 4** | Authentication Flow | Implement `LoginActivity` with Phone input, OTP verification, and PIN generation. |
| **Phase 5** | Main Screen & Chat List | Implement `MainActivity` with BottomNavigationView (Chats, Status, Calls) and `ChatListFragment`. |
| **Phase 6** | Chat Room & Bubbles | Build `ChatRoomActivity`, custom message bubbles with Quoted Reply and Voice playback. |
| **Phase 7** | Status Updates (Stories) | Implement `StoriesFragment` and full-screen `StoryViewerActivity` with viewer bottom sheet. |
| **Phase 8** | Audio & Media Features | Add CameraX media capture, audio recording, and Base64 Data URI fallbacks. |
