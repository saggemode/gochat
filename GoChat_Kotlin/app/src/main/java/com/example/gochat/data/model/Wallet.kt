package com.example.gochat.data.model

/**
 * Wallet + red packet (hóngbāo) models.
 *
 * All amounts are in MINOR units (kobo/cents), matching the backend. Format for
 * display by dividing by 100. Red packets move money only between in-app wallets;
 * real money enters the wallet via Paystack top-up.
 */
data class Wallet(
    val balance: Long,
    val currency: String,
    val ledger: List<WalletLedgerEntry> = emptyList()
)

data class WalletLedgerEntry(
    val amount: Long,
    val kind: String,
    val reference: String,
    val createdAt: String
)

/** Result of starting a Paystack top-up checkout. */
data class TopUpInit(
    val authorizationUrl: String,
    val reference: String,
    val amount: Long
)

/** Result of verifying a top-up: whether it was paid and the new balance. */
data class TopUpResult(
    val paid: Boolean,
    val balance: Long,
    val credited: Long
)

/** A newly created red packet (its id goes into the chat message). */
data class RedPacketCreated(
    val id: String,
    val totalAmount: Long,
    val count: Int,
    val message: String
)

/** Outcome of claiming a red packet. */
data class RedPacketClaim(
    val amount: Long,
    val balance: Long,
    val alreadyClaimed: Boolean
)

/** Full red packet state for the detail/claim screen. */
data class RedPacketDetail(
    val id: String,
    val senderId: String,
    val totalAmount: Long,
    val remainingAmount: Long,
    val totalCount: Int,
    val remainingCount: Int,
    val message: String,
    val claims: List<RedPacketClaimEntry>,
    val claimedByMe: Boolean,
    val myAmount: Long
)

data class RedPacketClaimEntry(
    val userId: String,
    val amount: Long,
    val claimedAt: String
)
