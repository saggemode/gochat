package com.example.gochat.core.marketplace

import android.graphics.Color
import com.example.gochat.data.model.Conversation
import com.example.gochat.data.model.Store

enum class SellerVerificationLevel(
    val level: Int,
    val title: String,
    val badgeColorInt: Int
) {
    NONE(0, "Unverified", Color.parseColor("#8898AA")),
    VERIFIED_MERCHANT(1, "Verified Merchant", Color.parseColor("#00A884")),
    OFFICIAL_STORE(2, "Official Store", Color.parseColor("#FFB703"));

    companion object {
        fun fromLevel(level: Int): SellerVerificationLevel {
            return entries.find { it.level == level } ?: if (level > 2) OFFICIAL_STORE else NONE
        }
    }
}

object SellerVerificationHelper {

    fun isStoreConversation(conversationId: String): Boolean {
        return conversationId.startsWith("conv_store_")
    }

    fun getVerificationLevel(
        conversationId: String,
        store: Store? = null,
        title: String = "",
        verifiedStoreIds: Set<String> = emptySet()
    ): SellerVerificationLevel {
        if (store != null && store.isVerified) {
            return if (store.totalSales >= 50 || store.rating >= 4.8) {
                SellerVerificationLevel.OFFICIAL_STORE
            } else {
                SellerVerificationLevel.VERIFIED_MERCHANT
            }
        }

        if (verifiedStoreIds.contains(conversationId) ||
            (store?.id != null && verifiedStoreIds.contains(store.id))
        ) {
            return SellerVerificationLevel.VERIFIED_MERCHANT
        }

        if (conversationId.startsWith("conv_store_")) {
            val lowerTitle = title.lowercase()
            return if (lowerTitle.contains("official") || lowerTitle.contains("flagship")) {
                SellerVerificationLevel.OFFICIAL_STORE
            } else {
                SellerVerificationLevel.VERIFIED_MERCHANT
            }
        }

        return SellerVerificationLevel.NONE
    }

    fun getTrustExplanation(level: SellerVerificationLevel, storeName: String = "This seller"): String {
        return when (level) {
            SellerVerificationLevel.OFFICIAL_STORE ->
                "⭐ Official Store: $storeName has highest-tier merchant accreditation with 100% GoChat Escrow protection. Funds remain locked until you inspect and approve your delivery."
            SellerVerificationLevel.VERIFIED_MERCHANT ->
                "🛡️ Verified Merchant: $storeName is identity-verified. All orders are backed by GoChat Escrow protection for safe and guaranteed transactions."
            SellerVerificationLevel.NONE ->
                "Standard Chat: Please use GoChat Escrow for all marketplace payments to guarantee buyer protection."
        }
    }
}
