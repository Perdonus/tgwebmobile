from __future__ import annotations

import sys
from pathlib import Path

PATTERNS = (
    'e: file:///',
    'w: file:///',
    'FAILURE:',
    'BUILD FAILED',
    'Execution failed for task',
    'Compilation error',
    'Caused by:',
    'What went wrong:',
    '* What went wrong:',
    'Exception:',
    'error:',
    'Unresolved reference',
    'Cannot access',
)


def collect_summary(lines: list[str]) -> list[str]:
    hit_indexes: list[int] = []
    for idx, line in enumerate(lines):
        if any(pattern in line for pattern in PATTERNS):
            hit_indexes.append(idx)

    if not hit_indexes:
        return lines[-140:]

    collected: list[str] = []
    seen: set[tuple[int, str]] = set()
    for idx in hit_indexes:
        start = max(0, idx - 4)
        end = min(len(lines), idx + 8)
        for line_no in range(start, end):
            line = lines[line_no]
            key = (line_no, line)
            if key in seen:
                continue
            seen.add(key)
            collected.append(line)
    return collected[-220:]


def encode_github_message(text: str) -> str:
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def main() -> int:
    if len(sys.argv) != 3:
        print('usage: extract_gradle_errors.py <gradle-log> <summary-log>', file=sys.stderr)
        return 2

    gradle_log = Path(sys.argv[1])
    summary_log = Path(sys.argv[2])
    if not gradle_log.exists():
        summary_log.write_text('Gradle log not found.\n')
        return 0

    content = gradle_log.read_text(errors='replace')
    lines = content.splitlines()
    summary_lines = collect_summary(lines)
    summary = '\n'.join(summary_lines).strip()
    if not summary:
        summary = 'Gradle log is empty.'

    summary_log.write_text(summary + '\n')
    print(f"::error title=Gradle failure summary::{encode_github_message(summary[-60000:])}")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
