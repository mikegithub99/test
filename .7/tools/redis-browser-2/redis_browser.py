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
        ("space", "toggle_expand", "Expand / Collapse"),
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
        self.last_rows = []
        self.expanded_types: set[str] = set()
        self.expanded_servers: set[str] = set()

    @property
    def interval(self) -> int:
        return REFRESH_OPTIONS[self.interval_index]

    def compose(self) -> ComposeResult:
        yield Header()
        yield DataTable(id="table", cursor_type="row", zebra_stripes=True)
        yield Static("Starting…", id="status")
        yield Static(
            "Enter/Space Expand-Collapse   1 Refresh   2 Pause   3 Slower   4 Faster   Q Quit",
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

    def load_hierarchy(self):
        """Read the complete hierarchy, including parent and depth metadata."""
        rows = []
        server_types = sorted(self.client.smembers("servers:{registry}:types"))

        for server_type in server_types:
            type_key = f"summary:type:{{{server_type}}}"
            type_counters = counter_values(self.client.hgetall(type_key))
            server_set_key = f"servers:{{registry}}:type:{server_type}"
            server_ids = sorted(self.client.smembers(server_set_key))
            rows.append((
                type_key, server_type, *type_counters, 0, None,
                bool(server_ids),
            ))

            for server_id in server_ids:
                server_key = f"summary:server:{{{server_type}}}:{server_id}"
                server_counters = counter_values(self.client.hgetall(server_key))
                jobs_set_key = f"jobs:server:{{{server_type}}}:{server_id}"
                references = sorted(self.client.smembers(jobs_set_key))
                rows.append((
                    server_key, f"SERVER: {server_id}", *server_counters,
                    1, type_key, bool(references),
                ))

                for reference in references:
                    job_key = f"job:{{{server_type}}}:{reference}"
                    job = self.client.hgetall(job_key)
                    if not job:
                        continue
                    job_id = job.get("jobId", reference)
                    status = job.get("status", "UNKNOWN").upper()
                    rows.append((
                        job_key, f"JOB: {job_id} [{status}]",
                        "1" if status == "PENDING" else "0",
                        "1" if status == "ACTIVE" else "0",
                        "1" if status == "SUSPENDED" else "0",
                        "1", 2, server_key, False,
                    ))
        return rows

    def visible_hierarchy(self, all_rows):
        """Return only rows whose parent levels are expanded."""
        by_key = {row[0]: row for row in all_rows}
        visible = []
        for row in all_rows:
            key, label, pending, active, suspended, total, level, parent, has_children = row
            if level == 0:
                visible.append(row)
            elif level == 1 and parent in self.expanded_types:
                visible.append(row)
            elif level == 2 and parent in self.expanded_servers:
                parent_server = by_key.get(parent)
                if parent_server and parent_server[7] in self.expanded_types:
                    visible.append(row)
        return visible

    async def refresh_view(self) -> None:
        if self._refreshing:
            return
        self._refreshing = True
        try:
            all_rows = await asyncio.to_thread(self.load_hierarchy)
            rows = self.visible_hierarchy(all_rows)
            table = self.query_one("#table", DataTable)
            old_cursor_row = table.cursor_row
            old_cursor_key = None
            if 0 <= old_cursor_row < len(self.last_rows):
                old_cursor_key = self.last_rows[old_cursor_row][0]
            had_focus = table.has_focus

            self.last_rows = rows
            table.clear()
            for row in rows:
                key, label, pending, active, suspended, total = row[:6]
                level, parent, has_children = row[6:9]
                is_expanded = (
                    (level == 0 and key in self.expanded_types)
                    or (level == 1 and key in self.expanded_servers)
                )
                marker = "[-]" if has_children and is_expanded else (
                    "[+]" if has_children else "   "
                )
                # Indentation belongs inside a fixed-width label field;
                # counters therefore start at the same screen column at
                # every hierarchy depth.
                item_label = f"{'  ' * level}{marker} {label}"
                display = (
                    f"{item_label:<52.52}"
                    f"{pending:>7} {active:>7} {suspended:>10} {total:>6}"
                )
                table.add_row(Text(display), key=key)

            restored_index = None
            if old_cursor_key:
                restored_index = next(
                    (i for i, row in enumerate(rows) if row[0] == old_cursor_key),
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
                f"{len(rows)} visible rows | {state} | Updated "
                f"{datetime.now().astimezone().strftime('%H:%M:%S %Z')}"
            )
        except Exception as exc:
            self.query_one("#status", Static).update(f"Redis error: {exc}")
        finally:
            self._refreshing = False

    async def action_toggle_expand(self) -> None:
        table = self.query_one("#table", DataTable)
        row_index = table.cursor_row
        if not 0 <= row_index < len(self.last_rows):
            return
        row = self.last_rows[row_index]
        key, _label, _p, _a, _s, _t, level, _parent, has_children = row
        if not has_children:
            return
        if level == 0:
            if key in self.expanded_types:
                self.expanded_types.remove(key)
                # Clear child expansion state when collapsing a server type.
                for child in self.last_rows:
                    if child[6] == 1 and child[7] == key:
                        self.expanded_servers.discard(child[0])
            else:
                self.expanded_types.add(key)
        elif level == 1:
            if key in self.expanded_servers:
                self.expanded_servers.remove(key)
            else:
                self.expanded_servers.add(key)
        await self.refresh_view()

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
