package com.example.gochat.data.repository

import com.example.gochat.data.api.GoChatApiService
import com.example.gochat.data.model.RedPacketClaim
import com.example.gochat.data.model.RedPacketClaimEntry
import com.example.gochat.data.model.RedPacketCreated
import com.example.gochat.data.model.RedPacketDetail
import com.example.gochat.data.model.TopUpInit
import com.example.gochat.data.model.TopUpResult
import com.example.gochat.data.model.Wallet
import com.example.gochat.data.model.WalletLedgerEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wallet + red packets. All amounts are minor units (kobo/cents). The backend
 * returns plain JSON objects, which we parse into typed models here.
 */
@Singleton
class WalletRepository @Inject constructor(
    private val api: GoChatApiService
) {

    suspend fun getWallet(): Result<Wallet> = try {
        val resp = api.getWallet()
        val body = resp.body()
        if (resp.isSuccessful && body != null) {
            Result.success(parseWallet(body))
        } else {
            Result.failure(Exception("Failed to load wallet"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun topUpInit(amountMinor: Long, email: String?): Result<TopUpInit> = try {
        val resp = api.walletTopUpInit(buildJsonObject {
            put("amount", amountMinor)
            if (!email.isNullOrBlank()) put("email", email)
        })
        val body = resp.body()
        if (resp.isSuccessful && body != null) {
            val url = body["authorization_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ref = body["reference"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank() || ref.isBlank()) Result.failure(Exception("Could not start top-up"))
            else Result.success(TopUpInit(url, ref, body["amount"]?.jsonPrimitive?.longOrNull ?: amountMinor))
        } else {
            Result.failure(Exception(errorMessage(resp.errorBody()?.string()) ?: "Could not start top-up"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun topUpVerify(reference: String): Result<TopUpResult> = try {
        val resp = api.walletTopUpVerify(reference)
        val body = resp.body()
        if (resp.isSuccessful && body != null) {
            Result.success(
                TopUpResult(
                    paid = body["paid"]?.jsonPrimitive?.booleanOrNull ?: false,
                    balance = body["balance"]?.jsonPrimitive?.longOrNull ?: 0L,
                    credited = body["credited"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            )
        } else {
            Result.failure(Exception("Could not verify top-up"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun createRedPacket(
        conversationId: String,
        totalAmountMinor: Long,
        count: Int,
        message: String
    ): Result<RedPacketCreated> = try {
        val resp = api.createRedPacket(buildJsonObject {
            put("conversation_id", conversationId)
            put("total_amount", totalAmountMinor)
            put("count", count)
            put("message", message)
        })
        val body = resp.body()
        if (resp.isSuccessful && body != null && body["id"] != null) {
            Result.success(
                RedPacketCreated(
                    id = body["id"]!!.jsonPrimitive.content,
                    totalAmount = body["total_amount"]?.jsonPrimitive?.longOrNull ?: totalAmountMinor,
                    count = body["count"]?.jsonPrimitive?.intOrNull ?: count,
                    message = body["message"]?.jsonPrimitive?.contentOrNull ?: message
                )
            )
        } else {
            Result.failure(Exception(errorMessage(resp.errorBody()?.string()) ?: "Could not create red packet"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getRedPacket(id: String): Result<RedPacketDetail> = try {
        val resp = api.getRedPacket(id)
        val body = resp.body()
        if (resp.isSuccessful && body != null) {
            Result.success(parseRedPacket(id, body))
        } else {
            Result.failure(Exception("Red packet not found"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun claimRedPacket(id: String): Result<RedPacketClaim> = try {
        val resp = api.claimRedPacket(id)
        val body = resp.body()
        if (resp.isSuccessful && body != null) {
            Result.success(
                RedPacketClaim(
                    amount = body["amount"]?.jsonPrimitive?.longOrNull ?: 0L,
                    balance = body["balance"]?.jsonPrimitive?.longOrNull ?: 0L,
                    alreadyClaimed = body["already_claimed"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            )
        } else {
            Result.failure(Exception(errorMessage(resp.errorBody()?.string()) ?: "This red packet is fully claimed"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun parseWallet(body: JsonObject): Wallet {
        val ledger = body["ledger"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            WalletLedgerEntry(
                amount = o["amount"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                kind = o["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reference = o["reference"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        } ?: emptyList()
        return Wallet(
            balance = body["balance"]?.jsonPrimitive?.longOrNull ?: 0L,
            currency = body["currency"]?.jsonPrimitive?.contentOrNull ?: "NGN",
            ledger = ledger
        )
    }

    private fun parseRedPacket(id: String, body: JsonObject): RedPacketDetail {
        val claims = body["claims"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            RedPacketClaimEntry(
                userId = o["user_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                amount = o["amount"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                claimedAt = o["claimed_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        } ?: emptyList()
        return RedPacketDetail(
            id = body["id"]?.jsonPrimitive?.contentOrNull ?: id,
            senderId = body["sender_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            totalAmount = body["total_amount"]?.jsonPrimitive?.longOrNull ?: 0L,
            remainingAmount = body["remaining_amount"]?.jsonPrimitive?.longOrNull ?: 0L,
            totalCount = body["total_count"]?.jsonPrimitive?.intOrNull ?: 0,
            remainingCount = body["remaining_count"]?.jsonPrimitive?.intOrNull ?: 0,
            message = body["message"]?.jsonPrimitive?.contentOrNull ?: "Best wishes!",
            claims = claims,
            claimedByMe = body["claimed_by_me"]?.jsonPrimitive?.booleanOrNull ?: false,
            myAmount = body["my_amount"]?.jsonPrimitive?.longOrNull ?: -1L
        )
    }

    /** Pulls the backend's `{"error": "..."}` message out of an error body, if present. */
    private fun errorMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
            obj["error"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }
}
