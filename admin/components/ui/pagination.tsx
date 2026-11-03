"use client"

import { ChevronLeft, ChevronRight } from "lucide-react"
import { Button } from "@/components/ui/button"

export function Pagination({
  offset,
  limit,
  total,
  onChange,
}: {
  offset: number
  limit: number
  total: number
  onChange: (offset: number) => void
}) {
  const from = total === 0 ? 0 : offset + 1
  const to = Math.min(offset + limit, total)
  const canPrev = offset > 0
  const canNext = offset + limit < total

  return (
    <div className="flex items-center justify-end gap-2">
      <span className="text-muted-foreground text-sm">
        {from}–{to} of {total}
      </span>
      <Button variant="outline" size="icon-sm" disabled={!canPrev} onClick={() => onChange(Math.max(0, offset - limit))} aria-label="Previous">
        <ChevronLeft />
      </Button>
      <Button variant="outline" size="icon-sm" disabled={!canNext} onClick={() => onChange(offset + limit)} aria-label="Next">
        <ChevronRight />
      </Button>
    </div>
  )
}
