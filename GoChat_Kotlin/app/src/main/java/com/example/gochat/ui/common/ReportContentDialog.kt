package com.example.gochat.ui.common

import android.content.Context
import com.example.gochat.data.model.ReportReason
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Reusable "Report" reason picker for any content type (story | product |
 * message | user). Shows the shared [ReportReason] list and hands the chosen
 * reason's apiValue back to the caller, which performs the actual submission
 * (e.g. chatRepository.reportContent(...)).
 */
object ReportContentDialog {

    fun show(
        context: Context,
        title: String,
        onReasonSelected: (reasonApiValue: String) -> Unit,
    ) {
        val reasons = ReportReason.values()
        val labels = reasons.map { it.displayLabel }.toTypedArray()
        var checked = 0
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels, 0) { _, which -> checked = which }
            .setPositiveButton("Report") { d, _ ->
                onReasonSelected(reasons[checked].apiValue)
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
