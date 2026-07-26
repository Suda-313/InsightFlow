"""Export the authorised TapTap review interval in resumable, low-rate batches.

The script intentionally stores only review content and stable review identifiers.
It keeps transient client metadata in process memory and accepts a pre-authorised
``TAPTAP_XUA`` value from the environment when the platform provides one.
"""

# Standard-library CSV writing keeps the generated file portable and auditable.
import argparse
import csv
import hashlib
import json
import os
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

# Scrapling is the authorised HTTP client selected for the collection workflow.
from scrapling.fetchers import Fetcher


# The game ID stays fixed, while date arguments create independent export files.
APP_ID = "714123"
BASE_URL = "https://www.taptap.cn"
TIMEZONE = timezone(timedelta(hours=8))

# Defaults preserve the original interval when the script is run without options.
ARGUMENTS = argparse.ArgumentParser(description="Export an authorised TapTap review date interval.")
ARGUMENTS.add_argument("--start", default="2026-07-12", help="inclusive date in YYYY-MM-DD")
ARGUMENTS.add_argument("--end", default="2026-07-25", help="inclusive date in YYYY-MM-DD")
DATE_ARGUMENTS = ARGUMENTS.parse_args()
START_DATE = datetime.fromisoformat(DATE_ARGUMENTS.start).date()
END_DATE = datetime.fromisoformat(DATE_ARGUMENTS.end).date()
if END_DATE < START_DATE:
    raise ValueError("--end must be on or after --start")
START = datetime.combine(START_DATE, datetime.min.time(), tzinfo=TIMEZONE)
END = datetime.combine(END_DATE, datetime.max.time(), tzinfo=TIMEZONE)
INTERVAL_NAME = f"{START_DATE.isoformat()}-to-{END_DATE.isoformat()}"

# Smaller batches leave enough time for checkpoint serialization before timeout.
PAGES_PER_BATCH = 30
OUTPUT_DIR = Path(__file__).resolve().parent
FINAL_CSV = OUTPUT_DIR / f"taptap-review-{INTERVAL_NAME}.csv"
PARTIAL_CSV = OUTPUT_DIR / f".taptap-review-{INTERVAL_NAME}.partial.csv"
STATE_FILE = OUTPUT_DIR / f".taptap-review-{INTERVAL_NAME}.state.json"


def build_headers() -> dict[str, str]:
    """Create required browser metadata without persisting any identifier."""

    # An official credential, if supplied by the authorised operator, takes priority.
    xua = os.environ.get("TAPTAP_XUA")
    if not xua:
        # The public web endpoint requires a syntactically valid WebApp X-UA header.
        xua = (
            "V=1&PN=WebApp&LANG=zh_CN&VN_CODE=102&LOC=CN&PLT=PC&DS=Android"
            f"&UID={uuid.uuid4()}&OS=Windows&OSV=10.0&DT=PC"
        )
    return {
        "User-Agent": "InsightFlow-authorized-review-export/1.0",
        "X-UA": xua,
    }


def initial_url() -> str:
    """Return the current public page endpoint in newest-review order."""

    return (
        f"{BASE_URL}/webapiv2/review/v2/list-by-app?app_id={APP_ID}"
        "&filter_platform=&from=0&label=&limit=10&mapping=&sort=new"
        "&source_type=&stage_type=2"
    )


def load_state() -> dict:
    """Load a prior batch checkpoint or initialise a clean collection state."""

    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    return {"next_url": initial_url(), "pages": 0, "all_old_pages": 0, "rows": []}


def save_state(state: dict) -> None:
    """Persist every page result so a forced timeout can resume safely."""

    # Replace only a complete temporary file so interruption preserves the prior state.
    temporary_file = STATE_FILE.with_name(f"{STATE_FILE.name}.tmp")
    temporary_file.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
    os.replace(temporary_file, STATE_FILE)


def row_from_item(item: dict) -> dict | None:
    """Minimise one API record into the CSV contract, excluding profile/device data."""

    moment = item.get("moment") or {}
    review = moment.get("review") or {}
    identification = item.get("identification") or moment.get("id_str") or review.get("id")
    created_time = moment.get("created_time")
    if not identification or not created_time:
        return None
    occurred_at = datetime.fromtimestamp(int(created_time), TIMEZONE)
    if not START <= occurred_at <= END:
        return None
    contents = review.get("contents") or {}
    feedback_text = (contents.get("raw_text") or contents.get("text") or "").strip()
    if not feedback_text:
        return None
    review_id = review.get("id") or str(identification).split(":")[-1]
    return {
        "feedback_text": feedback_text,
        "occurred_at": occurred_at.isoformat(),
        "source": "taptap_review",
        "external_ref": str(identification),
        "rating": review.get("score", ""),
        "platform": "TapTap",
        "source_url": f"{BASE_URL}/review/{review_id}",
    }


def write_csv(path: Path, rows: list[dict]) -> None:
    """Write a UTF-8 review table with a fixed, import-friendly header."""

    fields = [
        "feedback_text",
        "occurred_at",
        "source",
        "external_ref",
        "rating",
        "platform",
        "source_url",
    ]
    with path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        writer.writerows(sorted(rows, key=lambda row: row["occurred_at"]))


def main() -> None:
    """Collect one resumable batch and publish the final CSV only when complete."""

    state = load_state()
    existing = {row["external_ref"] for row in state["rows"]}
    headers = build_headers()
    batch_pages = 0
    completed = False

    while state.get("next_url") and batch_pages < PAGES_PER_BATCH:
        response = Fetcher.get(state["next_url"], headers=headers)
        payload = json.loads(response.body)
        if response.status != 200 or not payload.get("success"):
            message = (payload.get("data") or {}).get("msg") or "unknown response"
            raise RuntimeError(f"HTTP {response.status}: {message}")
        data = payload.get("data") or {}
        items = data.get("list") or []
        if not items:
            completed = True
            break

        page_times = []
        for item in items:
            moment = item.get("moment") or {}
            created_time = moment.get("created_time")
            if created_time:
                page_times.append(datetime.fromtimestamp(int(created_time), TIMEZONE))
            row = row_from_item(item)
            if row and row["external_ref"] not in existing:
                state["rows"].append(row)
                existing.add(row["external_ref"])

        # The newest list contains occasional promoted historical reviews. Requiring
        # three wholly old pages avoids treating a single historical insertion as EOF.
        if page_times and all(occurred_at < START for occurred_at in page_times):
            state["all_old_pages"] += 1
        else:
            state["all_old_pages"] = 0

        state["pages"] += 1
        batch_pages += 1
        if state["all_old_pages"] >= 3:
            completed = True
            state["next_url"] = None
        else:
            next_page = data.get("next_page")
            state["next_url"] = (
                BASE_URL + next_page if next_page and next_page.startswith("/") else next_page
            )
        save_state(state)
        if not completed and state["next_url"]:
            time.sleep(1)

    write_csv(PARTIAL_CSV, state["rows"])
    if completed:
        write_csv(FINAL_CSV, state["rows"])
    csv_path = FINAL_CSV if completed else PARTIAL_CSV
    print(
        json.dumps(
            {
                "completed": completed,
                "pages": state["pages"],
                "rows": len(state["rows"]),
                "csv": str(csv_path),
                "sha256": hashlib.sha256(csv_path.read_bytes()).hexdigest(),
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
