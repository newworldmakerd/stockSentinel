"""Offline comparison of read-only PostgreSQL inventory and V1; no DB connection."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sql = (root / "backend/src/main/resources/db/migration/V1__initial_schema.sql").read_text(encoding="utf-8")
inventory = json.loads((root / "docs/database-public-inventory.json").read_text(encoding="utf-8"))
actual = {(r["table_name"], r["column_name"]): r for r in inventory["columns"]}
expected = {}
constraints = set()
differences = []


def normalize(text):
    return re.sub(r"\s+", "", text).lower()


def default(value):
    return None if value is None else normalize(re.sub(r"::character varying", "", value))


for table, body in re.findall(r"CREATE TABLE (\w+) \((.*?)\n\);", sql, re.S):
    for line in body.splitlines():
        line = line.strip().rstrip(",")
        if line.startswith("UNIQUE("):
            constraints.add((table, "u", normalize(line)))
            continue
        match = re.match(r"(\w+)\s+(BIGINT|VARCHAR\(\d+\)|NUMERIC\(\d+,\d+\)|BOOLEAN|DATE|TIMESTAMPTZ|TEXT|INT|JSONB)(.*)", line)
        if not match:
            continue
        column, kind, tail = match.groups()
        kind = kind.lower().replace("varchar", "character varying").replace("timestamptz", "timestamp with time zone")
        kind = {"int": "integer"}.get(kind, kind)
        value = re.search(r"DEFAULT\s+('.*?'::jsonb|'.*?'|now\(\)|TRUE|FALSE|\d+)", tail)
        expected[(table, column)] = (kind, "NO" if "NOT NULL" in tail or "PRIMARY KEY" in tail else "YES", "YES" if "GENERATED" in tail else "NO", value[1] if value else None)
        if "PRIMARY KEY" in tail:
            constraints.add((table, "p", normalize(f"PRIMARY KEY ({column})")))
        if "UNIQUE" in tail:
            constraints.add((table, "u", normalize(f"UNIQUE ({column})")))
        fk = re.search(r"REFERENCES (\w+)\((\w+)\)", tail)
        if fk:
            constraints.add((table, "f", normalize(f"FOREIGN KEY ({column}) REFERENCES {fk[1]}({fk[2]})")))

for key, value in expected.items():
    row = actual.get(key)
    if row is None:
        differences.append(f"MISSING COLUMN {key}")
        continue
    received = (row["sql_type"], row["is_nullable"], row["is_identity"], row["column_default"])
    if (normalize(value[0]), *value[1:3], default(value[3])) != (normalize(received[0]), *received[1:3], default(received[3])):
        differences.append(f"COLUMN DIFFERENCE {key}: expected={value} actual={received}")
for key in actual.keys() - expected.keys():
    differences.append(f"EXTRA COLUMN {key}")
# PG18 also exposes NOT NULL as contype=n. Nullability was checked above.
received_constraints = {(r["table_name"], r["contype"], normalize(r["definition"])) for r in inventory["constraints"] if r["contype"] != "n"}
for item in constraints - received_constraints:
    differences.append(f"MISSING CONSTRAINT {item}")
for item in received_constraints - constraints:
    differences.append(f"EXTRA CONSTRAINT {item}")
indexes = {r["indexname"]: r["indexdef"] for r in inventory["indexes"]}
explicit_indexes = re.findall(r"CREATE INDEX (\w+) ON (\w+)\s*\((.*?)\);", sql)
for name, table, columns in explicit_indexes:
    definition = normalize(indexes.get(name, ""))
    if f"onpublic.{table}" not in definition or normalize("(" + columns + ")") not in definition:
        differences.append(f"INDEX DIFFERENCE {name}")
summary = f"Compared {len(expected)} columns, {len(constraints)} PK/UNIQUE/FK constraints and {len(explicit_indexes)} explicit indexes across 10 tables.\n"
summary += "MATCH: existing public core schema agrees with V1.\n" if not differences else "\n".join(sorted(differences)) + "\n"
(root / "docs/database-v1-comparison.txt").write_text(summary, encoding="utf-8")
print(summary)
raise SystemExit(bool(differences))