"""Read-only Textual browser for the RedisJobStatus .7 keyspace."""

from __future__ import annotations

import asyncio
import json
import os
from collections import Counter
from datetime import datetime
from typing import Any

import redis
from redis.cluster import RedisCluster
from textual import on
from textual.app import App, ComposeResult
from textual.containers import Horizontal
from textual.widgets import Button, DataTable, Footer, Header, Select, Static

REFRESH_OPTIONS = [
    ("1 second", 1), ("2 seconds", 2), ("5 seconds", 5),
    ("10 seconds", 10), ("15 seconds", 15), ("30 seconds", 30),
]
KEY_PATTERNS = [
    "servers:{registry}:types",
    "servers:{registry}:type:*",
    "summary:type:{*}",
    "summary:server:{*}:*",
    "job:{*}:*",
    "jobs:type:{*}",
    "jobs:server:{*}:*",
    "heartbeat:server:{*}:*",
]


def make_client():
    url = os.getenv("REDIS_URL", "redis://127.0.0.1:6379")
    if os.getenv("REDIS_CLUSTER", "").lower() in {"1", "true", "yes"}:
        return RedisCluster.from_url(url, decode_responses=True)
    return redis.Redis.from_url(url, decode_responses=True)


def redis_text(value: Any) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value)


class RedisBrowser(App):
    TITLE = "Redis Job Status Browser (.7)"
    SUB_TITLE = "Read-only Redis inspector"
    BINDINGS = [
        ("r", "refresh_now", "Refresh"),
        ("1", "show_overview", "Overview"),
        ("2", "show_jobs", "Jobs"),
        ("3", "show_keys", "Raw keys"),
        ("e", "expand_all", "Expand all"),
        ("c", "collapse_all", "Collapse all"),
        ("q", "quit", "Quit"),
        ("up", "move_job_row_up", "", False, None, True),
        ("down", "move_job_row_down", "", False, None, True),
    ]

    CSS = """
    Screen { layout: vertical; }
    #toolbar { height: 3; padding: 0 1; }
    #mode-label { width: 7; content-align: left middle; }
    #refresh-label { width: 9; content-align: center middle; }
    #interval { width: 18; }
    #table { height: 1fr; }
    #details { height: 12; border: round $accent; padding: 1; overflow-y: auto; }
    #status { height: 1; padding: 0 1; color: $text-muted; }
    Button { margin-right: 1; }
    """

    def __init__(self):
        super().__init__()
        self.client = make_client()
        self.mode = "overview"
        self.interval = 2
        self.selected_key: str | None = None
        self.last_rows: list[tuple[str, str, str, str]] = []
        self._refreshing = False
        self._timer = None
        self.collapsed = False

    def compose(self) -> ComposeResult:
        yield Header()
        with Horizontal(id="toolbar"):
            yield Static("View:", id="mode-label")
            yield Button("Overview", id="overview", variant="primary")
            yield Button("Jobs", id="jobs")
            yield Button("Raw keys", id="keys")
            yield Static("Every:", id="refresh-label")
            yield Select(REFRESH_OPTIONS, value=2, allow_blank=False, id="interval")
            yield Button("Refresh", id="refresh")
        yield DataTable(id="table", cursor_type="row", zebra_stripes=True)
        yield Static("Select a row to inspect its Redis data.", id="details")
        yield Static("Starting…", id="status")
        yield Footer()

    async def on_mount(self) -> None:
        self.set_table_columns()
        self._timer = self.set_interval(self.interval, self.refresh_view)
        await self.refresh_view()

    async def on_unmount(self) -> None:
        try:
            await asyncio.to_thread(self.client.close)
        except Exception:
            pass

    def set_table_columns(self) -> None:
        table = self.query_one("#table", DataTable)
        table.clear(columns=True)
        if self.mode == "overview":
            table.add_columns("Server Type", "Server ID", "Status / Heartbeat", "Details")
        elif self.mode == "jobs":
            table.add_columns("Job ID", "Server Type / Server ID", "Status", "Details")
        else:
            table.add_columns("Redis Key", "Type", "Details", "Info")

    def set_mode(self, mode: str) -> None:
        self.mode = mode
        self.set_table_columns()
        for name in ("overview", "jobs", "keys"):
            self.query_one(f"#{name}", Button).variant = (
                "primary" if name == mode else "default"
            )
        self.selected_key = None
        self.query_one("#details", Static).update(
            "Select a row to inspect its Redis data."
        )

    @on(Button.Pressed)
    async def button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id in {"overview", "jobs", "keys"}:
            self.set_mode(event.button.id)
            await self.refresh_view()
            self.query_one("#table", DataTable).focus()
        elif event.button.id == "refresh":
            await self.refresh_view()

    @on(Select.Changed, "#interval")
    def interval_changed(self, event: Select.Changed) -> None:
        if event.value is not Select.BLANK:
            self.interval = int(event.value)
            if self._timer:
                self._timer.stop()
            self._timer = self.set_interval(self.interval, self.refresh_view)
            self.update_status(f"Auto-refresh every {self.interval}s")

    @on(DataTable.RowHighlighted)
    async def row_highlighted(self, event: DataTable.RowHighlighted) -> None:
        key = str(event.row_key.value)
        self.selected_key = key or None
        if self.selected_key:
            await self.show_details(self.selected_key)

    @on(DataTable.RowSelected)
    async def row_selected(self, event: DataTable.RowSelected) -> None:
        key = str(event.row_key.value)
        self.selected_key = key or None
        if self.selected_key:
            await self.show_details(self.selected_key)

    async def refresh_view(self) -> None:
        if self._refreshing:
            return
        self._refreshing = True
        try:
            if self.mode == "overview":
                rows = await asyncio.to_thread(self.load_overview)
                if self.collapsed:
                    rows = [row for row in rows if row[1].startswith("[TYPE]")]
            elif self.mode == "jobs":
                rows = await asyncio.to_thread(self.load_jobs)
            else:
                rows = await asyncio.to_thread(self.load_keys)

            self.last_rows = rows
            table = self.query_one("#table", DataTable)
            old_key = self.selected_key
            table.clear()
            for key, name, col2, col3 in rows:
                if self.mode == "overview":
                    table.add_row(name, col2, col3, key, key=key)
                else:
                    table.add_row(name, col2, col3, key, key=key)

            if old_key and any(row[0] == old_key for row in rows):
                self.selected_key = old_key
            else:
                self.selected_key = None
                self.query_one("#details", Static).update(
                    "Select a row to inspect its Redis data."
                )
            self.update_status(
                f"{len(rows)} rows | Updated "
                f"{datetime.now().astimezone().strftime('%H:%M:%S %Z')} "
                f"| Auto-refresh {self.interval}s"
            )
        except Exception as exc:
            self.update_status(f"Redis error: {exc}")
        finally:
            self._refreshing = False

    def load_overview(self) -> list[tuple[str, str, str, str]]:
        rows = []
        types = sorted(self.client.smembers("servers:{registry}:types"))
        for server_type in types:
            summary_key = f"summary:type:{{{server_type}}}"
            summary = self.client.hgetall(summary_key)
            counts = " ".join(f"{k}={v}" for k, v in sorted(summary.items()))
            rows.append((
                summary_key, server_type, "", counts or "No summary",
            ))
            server_key = f"servers:{{registry}}:type:{server_type}"
            for server_id in sorted(self.client.smembers(server_key)):
                heartbeat_key = f"heartbeat:server:{{{server_type}}}:{server_id}"
                ttl = self.client.ttl(heartbeat_key)
                server_summary_key = f"summary:server:{{{server_type}}}:{server_id}"
                server_summary = self.client.hgetall(server_summary_key)
                server_counts = " ".join(
                    f"{k}={v}" for k, v in sorted(server_summary.items())
                )
                heartbeat = f"heartbeat TTL={ttl}s" if ttl >= 0 else "heartbeat missing"
                rows.append((
                    server_summary_key, server_type, server_id,
                    f"{heartbeat}; {server_counts}",
                ))
        return rows

    def load_jobs(self) -> list[tuple[str, str, str, str]]:
        jobs = []
        types = sorted(self.client.smembers("servers:{registry}:types"))
        for server_type in types:
            for reference in self.client.smembers(f"jobs:type:{{{server_type}}}"):
                job_key = f"job:{{{server_type}}}:{reference}"
                record = self.client.hgetall(job_key)
                if record:
                    jobs.append((job_key, server_type, reference, record))
        duplicate_counts = Counter(record.get("jobId", "") for _, _, _, record in jobs)
        rows = []
        for job_key, server_type, reference, record in sorted(
            jobs,
            key=lambda item: (
                item[1], item[3].get("serverId", ""), item[3].get("jobId", "")
            ),
        ):
            job_id = record.get("jobId", reference)
            display_id = reference if duplicate_counts[job_id] > 1 else job_id
            rows.append((
                job_key, display_id,
                f"{server_type}/{record.get('serverId', '?')}",
                record.get("status", "UNKNOWN"),
            ))
        return rows

    def load_keys(self) -> list[tuple[str, str, str, str]]:
        found = set()
        for pattern in KEY_PATTERNS:
            found.update(self.client.scan_iter(match=pattern, count=500))
        rows = []
        for key in sorted(found):
            kind = self.client.type(key)
            if kind == "hash":
                detail = f"{self.client.hlen(key)} fields"
            elif kind == "set":
                detail = f"{self.client.scard(key)} members"
            elif kind == "string":
                detail = f"TTL={self.client.ttl(key)}s"
            else:
                detail = kind
            rows.append((key, key, "", f"{kind}: {detail}"))
        return rows

    async def show_details(self, key: str) -> None:
        try:
            detail = await asyncio.to_thread(self.read_key, key)
            self.query_one("#details", Static).update(detail)
        except Exception as exc:
            self.query_one("#details", Static).update(f"{key}\nError: {exc}")

    def read_key(self, key: str) -> str:
        kind = self.client.type(key)
        ttl = self.client.ttl(key)
        lines = [f"Redis key: {key}", f"Type: {kind} | TTL: {ttl}s"]
        if kind == "hash":
            lines.extend(
                f"{field}: {value}"
                for field, value in sorted(self.client.hgetall(key).items())
            )
        elif kind == "set":
            lines.extend(sorted(self.client.smembers(key)))
        elif kind == "string":
            lines.append(redis_text(self.client.get(key)))
        elif kind == "list":
            lines.extend(self.client.lrange(key, 0, -1))
        elif kind == "zset":
            lines.extend(
                f"{member}: {score}"
                for member, score in self.client.zrange(key, 0, -1, withscores=True)
            )
        elif kind == "stream":
            lines.extend(
                json.dumps(item, ensure_ascii=False)
                for item in self.client.xrange(key)
            )
        else:
            lines.append("(empty or unsupported Redis value)")
        return "\n".join(lines)

    def update_status(self, message: str) -> None:
        self.query_one("#status", Static).update(message)

    async def action_move_job_row_up(self) -> None:
        if self.mode == "jobs":
            table = self.query_one("#table", DataTable)
            table.focus()
            table.move_cursor(
                row=max(0, table.cursor_row - 1),
                column=table.cursor_column,
                animate=False,
            )

    async def action_move_job_row_down(self) -> None:
        if self.mode == "jobs":
            table = self.query_one("#table", DataTable)
            table.focus()
            table.move_cursor(
                row=min(table.row_count - 1, table.cursor_row + 1),
                column=table.cursor_column,
                animate=False,
            )

    async def action_refresh_now(self) -> None:
        await self.refresh_view()

    async def action_show_overview(self) -> None:
        self.set_mode("overview")
        await self.refresh_view()

    async def action_show_jobs(self) -> None:
        self.set_mode("jobs")
        await self.refresh_view()

    async def action_show_keys(self) -> None:
        self.set_mode("keys")
        await self.refresh_view()

    async def action_expand_all(self) -> None:
        self.collapsed = False
        self.set_mode("overview")
        await self.refresh_view()

    async def action_collapse_all(self) -> None:
        self.collapsed = True
        self.set_mode("overview")
        await self.refresh_view()


if __name__ == "__main__":
    RedisBrowser().run()
