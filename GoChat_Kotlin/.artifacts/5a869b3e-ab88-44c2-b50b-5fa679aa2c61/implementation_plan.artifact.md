# Implementation Plan: Messaging Enhancements

This plan details the implementation of four major messaging enhancements: Message Reactions, Editing & Deletion, Starred Messages, and Forwarding with Context.

## User Review Required

> [!IMPORTANT]
> - **Database Migration**: Adding new fields to the `Message` entity will require a Room database migration or a "destructive migration" (clearing local data) if we are in early development.
> - **WebSocket/Backend Sync**: These features rely on backend support to sync changes across devices. I will implement the local logic and placeholders for API/WebSocket integration.

## Proposed Changes

### 1. Data Models & Database

#### [MODIFY] [Message.kt](file:///C:/Users/hi/Desktop/whatsapp_golang/GoChat_Kotlin/app/src/main/java/com/example/gochat/data/model/Message.kt)
- Add `reactions: List<Reaction>` (JSON serialized).
- Add `isEdited: Boolean`, `isDeleted: Boolean`, `isStarred: Boolean`.
- Add `isForwarded: Boolean`, `originalSenderName: String?`.
- Add a new `Reaction` data class.

#### [MODIFY] [ChatDao.kt](file:///C:/Users/hi/Desktop/whatsapp_golang/GoChat_Kotlin/app/src/main/java/com/example/gochat/data/db/ChatDao.kt)
- Add queries for toggling stars, marking as deleted, and updating content.
- Add a query to fetch all starred messages.

#### [MODIFY] [Converters.kt](file:///C:/Users/hi/Desktop/whatsapp_golang/GoChat_Kotlin/app/src/main/java/com/example/gochat/data/db/Converters.kt)
- Add `TypeConverter` for `List<Reaction>`.

---

### 2. UI Layouts

#### [MODIFY] [item_message_me.xml](file:///C:/Users/hi/Desktop/whatsapp_golang/GoChat_Kotlin/app/src/main/res/layout/item_message_me.xml) & [item_message_other.xml](file:///C:/Users/hi/Desktop/whatsapp_golang/GoChat_Kotlin/app/src/main/res/layout/item_message_other.xml)
- Add a `FlexboxLayout` or a horizontal `LinearLayout` for reactions.
- Add an "Edited" label next to the timestamp.
- Add a "Forwarded" label at the top of the bubble.
- Add a star icon overlay for starred messages.
- Update the bubble to show a "Deleted" state UI.

---

### 3. Business Logic (Repository & ViewModel)

#### [MODIFY] [ChatRepository.kt](file:///C:/Users/hi/Desktop/whatsapp_golang/GoChat_Kotlin/app/src/main/java/com/example/gochat/data/repository/ChatRepository.kt)
- Implement `addReaction`, `editMessage`, `deleteMessage`, `toggleStar`.
- Implement `forwardMessage` (cloning message to new conversations).

#### [MODIFY] [ChatRoomViewModel.kt](file:///C:/Users/hi/Desktop/whatsapp_golang/GoChat_Kotlin/app/src/main/java/com/example/gochat/ui/chat/ChatRoomViewModel.kt)
- Add states for "Editing Mode".
- Handle long-press actions (show context menu for reactions, edit, star, forward).

---

### 4. UI Implementation

#### [MODIFY] [MessageAdapter.kt](file:///C:/Users/hi/Desktop/whatsapp_golang/GoChat_Kotlin/app/src/main/java/com/example/gochat/ui/chat/MessageAdapter.kt)
- Update `bind` logic to handle the new fields.
- Implement reaction list rendering.

#### [NEW] [MessageContextMenu.kt] (or similar)
- A bottom sheet or popup for message actions.

## Verification Plan

### Automated Tests
- Unit tests for `Message.fromJson` with the new fields.
- DAO tests for starring and deleting messages.

### Manual Verification
- Long-press a message and add a reaction.
- Edit a message and verify the "Edited" label appears.
- Star a message and verify it appears in a (to-be-created) Starred Messages list.
- Delete a message for everyone and verify the content is replaced with a "Deleted" placeholder.
- Forward a message to another chat and verify the "Forwarded" label.
