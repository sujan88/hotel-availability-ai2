#!/usr/bin/env python3
"""Search AI2's allow-listed JSON event logs, including rotated gzip archives."""
import argparse
import glob
import gzip
import json
import re
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    query = parser.add_mutually_exclusive_group(required=True)
    query.add_argument('--trace-id')
    query.add_argument('--conversation-id')
    parser.add_argument('--file', action='append', help='File/glob; repeat for multiple sources. Default: logs/ai-chat.jsonl')
    parser.add_argument('--event', action='append', help='Filter event name; repeat for multiple event types.')
    args = parser.parse_args()
    key = 'traceId' if args.trace_id else 'conversationId'
    value = args.trace_id or args.conversation_id
    pattern = r'[a-f0-9]{32}' if args.trace_id else r'[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}'
    if not re.fullmatch(pattern, value):
        parser.error('Provide an actual generated trace ID or conversation UUID.')
    paths = sorted({path for source in (args.file or ['logs/ai-chat.jsonl']) for path in glob.glob(source)})
    if not paths:
        print('No matching log files. Run from AI2 or supply --file PATH.', file=sys.stderr)
        return 2
    records = []
    invalid = 0
    for path in paths:
        opener = gzip.open if path.endswith('.gz') else open
        try:
            with opener(path, 'rt', encoding='utf-8') as stream:
                for line in stream:
                    try:
                        record = json.loads(line)
                    except json.JSONDecodeError:
                        invalid += 1
                        continue
                    if isinstance(record, dict) and record.get(key) == value and (not args.event or record.get('event') in args.event):
                        records.append(record)
        except OSError:
            print('Unable to read a selected log file.', file=sys.stderr)
            return 2
    records.sort(key=lambda row: row.get('@timestamp', ''))
    json.dump(records, sys.stdout, indent=2)
    sys.stdout.write('\n')
    if invalid:
        print(f'Skipped {invalid} incomplete/invalid JSON lines.', file=sys.stderr)
    if not records:
        print('No events matched; check the ID and retained/rotated files.', file=sys.stderr)
    return 0 if records else 1


if __name__ == '__main__':
    raise SystemExit(main())
