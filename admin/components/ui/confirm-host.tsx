"use client"

import { useState } from "react"
import { Dialog } from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useConfirmState, resolveConfirm, type ConfirmState } from "@/lib/confirm"

export function ConfirmHost() {
  const state = useConfirmState()
  if (!state) return null
  // Keyed so each new confirm remounts with fresh reason state.
  return <ConfirmInner key={state.id} state={state} />
}

function ConfirmInner({ state }: { state: NonNullable<ConfirmState> }) {
  const [reason, setReason] = useState("")
  const canConfirm = !state.reasonRequired || reason.trim().length > 0

  return (
    <Dialog open onClose={() => resolveConfirm(false, "")} title={state.title} description={state.message}>
      <div className="space-y-3">
        {state.withReason ? (
          <div className="space-y-1.5">
            <label className="text-sm font-medium">
              {state.reasonLabel ?? "Reason"}
              {state.reasonRequired ? " *" : " (optional)"}
            </label>
            <Input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Recorded in the audit log" />
          </div>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={() => resolveConfirm(false, "")}>Cancel</Button>
          <Button
            variant={state.danger ? "destructive" : "default"}
            disabled={!canConfirm}
            onClick={() => resolveConfirm(true, reason.trim())}
          >
            {state.confirmLabel ?? "Confirm"}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
