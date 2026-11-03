"""Counter-focused, read-only browser for the RedisJobStatus .7 keyspace."""

from __future__ import annotations

import asyncio
import os
from datetime import datetime
from typing import Any

import redis
from redis.cluster import RedisCluster
from textual.app import App, ComposeResult
from textual.containers import Horizontal
from textual.widgets import DataTable, Header, Static
from rich.text import Text

REFRESH_OPTIONS = (1, 2, 5, 10, 15, 30)


def make_client():
    url = os.getenv("REDIS_URL", "redis://127.0.0.1:6379")
    if os.getenv("REDIS_CLUSTER", "").lower() in {"1", "true", "yes"}:
        return RedisCluster.from_url(url, decode_responses=True)
    return redis.Redis.from_url(url, decode_responses=True)


def counter_values(summary: dict[str, str]) -> tuple[str, str, str, str]:
    """Return the four materialized lifecycle counters in display order."""
    return tuple(
        str(int(summary.get(name, "0")))
        for name in ("pending", "active", "suspended", "total")
    )


class RedisBrowser(App):
    TITLE = "Redis Job Counters (.7)"
    SUB_TITLE = "Live hierarchy | read-only"

    BINDINGS = [
        ("1", "refresh_now", "Refresh"),
        ("2", "toggle_pause", "Pause / Resume"),
        ("3", "slower", "Slower"),
        ("4", "faster", "Faster"),
        ("q", "quit", "Quit"),
    ]

    CSS = """
    Screen { layout: vertical; }
    #table { height: 1fr; }
    #status { height: 1; padding: 0 1; color: $text-muted; }
    #controls {
        height: 1;
        padding: 0 1;
        background: $boost;
        color: $text;
    }
    """

    def __init__(self) -> None:
        super().__init__()
        self.client = make_client()
        self.interval_index = REFRESH_OPTIONS.index(2)
        self.paused = False
        self._refreshing = False
        self._timer = None
        self.last_rows: list[tuple[str, str, str, str, str, str]] = []

    @property
    def interval(self) -> int:
        return REFRESH_OPTIONS[self.interval_index]

    def compose(self) -> ComposeResult:
        yield Header()
        yield DataTable(id="table", cursor_type="row", zebra_stripes=True)
        yield Static("Starting…", id="status")
        yield Static(
            "1 Refresh   2 Pause/Resume   3 Slower   4 Faster   Q Quit",
            id="controls",
        )

    async def on_mount(self) -> None:
        table = self.query_one("#table", DataTable)
        # Keep the hierarchy and counters in one column. Separate DataTable
        # columns can horizontally scroll the hierarchy label out of view on
        # narrower terminals.
        table.add_column(
            "Hierarchy                                      Pending  Active  Suspended  Total",
            key="display",
        )
        self._timer = self.set_interval(self.interval, self.refresh_view)
        table.focus()
        await self.refresh_view()

    async def on_unmount(self) -> None:
        if self._timer:
            self._timer.stop()
        try:
            await asyncio.to_thread(self.client.close)
        except Exception:
            pass

    def load_hierarchy(self) -> list[tuple[str, str, str, str, str, str]]:
        rows: list[tuple[str, str, str, str, str, str]] = []
        server_types = sorted(self.client.smembers("servers:{registry}:types"))

        for server_type in server_types:
            type_summary_key = f"summary:type:{{{server_type}}}"
            type_counters = counter_values(self.client.hgetall(type_summary_key))
            rows.append((
                type_summary_key,
                server_type,
                *type_counters,
            ))

            server_set_key = f"servers:{{registry}}:type:{server_type}"
            server_ids = sorted(self.client.smembers(server_set_key))

            for server_id in server_ids:
                server_summary_key = (
                    f"summary:server:{{{server_type}}}:{server_id}"
                )
                server_counters = counter_values(
                    self.client.hgetall(server_summary_key)
                )
                rows.append((
                    server_summary_key,
                    f"  SERVER: {server_id}",
                    *server_counters,
                ))

                server_jobs_key = (
                    f"jobs:server:{{{server_type}}}:{server_id}"
                )
                references = sorted(self.client.smembers(server_jobs_key))
                for index, reference in enumerate(references):
                    job_key = f"job:{{{server_type}}}:{reference}"
                    job = self.client.hgetall(job_key)
                    if not job:
                        continue
                    job_id = job.get("jobId", reference)
                    status = job.get("status", "UNKNOWN").upper()
                    rows.append((
                        job_key,
                        f"    JOB: {job_id} [{status}]",
                        "1" if status == "PENDING" else "0",
                        "1" if status == "ACTIVE" else "0",
                        "1" if status == "SUSPENDED" else "0",
                        "1",
                    ))

        return rows

    async def refresh_view(self) -> None:
        if self._refreshing:
            return
        self._refreshing = True
        try:
            rows = await asyncio.to_thread(self.load_hierarchy)
            table = self.query_one("#table", DataTable)
            old_cursor_row = table.cursor_row
            old_cursor_key = None
            if 0 <= old_cursor_row < len(self.last_rows):
                old_cursor_key = self.last_rows[old_cursor_row][0]
            had_focus = table.has_focus

            self.last_rows = rows
            table.clear()
            for row in rows:
                # Normalize defensively so a malformed/older row shape cannot
                # abort the entire refresh cycle.
                values = tuple(row[:6])
                if len(values) < 6:
                    values += ("0",) * (6 - len(values))
                key, label, pending, active, suspended, total = values
                display = (
                    f"{label:<44} {pending:>7} {active:>7} "
                    f"{suspended:>10} {total:>6}"
                )
                # Disable Rich markup parsing so bracketed IDs/status text stay literal.
                table.add_row(Text(display, markup=False), key=key)

            restored_index = None
            if old_cursor_key:
                restored_index = next(
                    (
                        index for index, row in enumerate(rows)
                        if row[0] == old_cursor_key
                    ),
                    None,
                )
            if restored_index is None and rows:
                restored_index = min(max(old_cursor_row, 0), len(rows) - 1)
            if restored_index is not None:
                table.move_cursor(row=restored_index, column=0, animate=False)

            if had_focus:
                table.focus()

            state = "PAUSED" if self.paused else f"refresh every {self.interval}s"
            self.query_one("#status", Static).update(
                f"{len(rows)} rows | {state} | Updated "
                f"{datetime.now().astimezone().strftime('%H:%M:%S %Z')}"
            )
        except Exception as exc:
            self.query_one("#status", Static).update(f"Redis error: {exc}")
        finally:
            self._refreshing = False

    def restart_timer(self) -> None:
        if self._timer:
            self._timer.stop()
        self._timer = None
        if not self.paused:
            self._timer = self.set_interval(self.interval, self.refresh_view)

    async def action_refresh_now(self) -> None:
        await self.refresh_view()

    async def action_toggle_pause(self) -> None:
        self.paused = not self.paused
        self.restart_timer()
        await self.refresh_view()

    async def action_slower(self) -> None:
        self.interval_index = min(
            self.interval_index + 1, len(REFRESH_OPTIONS) - 1
        )
        self.restart_timer()
        await self.refresh_view()

    async def action_faster(self) -> None:
        self.interval_index = max(self.interval_index - 1, 0)
        self.restart_timer()
        await self.refresh_view()


if __name__ == "__main__":
    RedisBrowser().run()
