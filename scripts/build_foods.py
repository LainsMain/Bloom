#!/usr/bin/env python3
"""Builds app/src/main/assets/generic_foods.db from USDA FoodData Central SR Legacy (public domain).

Usage: python3 -I scripts/build_foods.py <path-to-FoodData_Central_sr_legacy_food_csv_dir> <out.db>
Download: https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip
"""
import csv, json, os, sqlite3, sys
from collections import defaultdict

src, out = sys.argv[1], sys.argv[2]
NUTRIENTS = {"1008": "kcal", "1003": "protein", "1005": "carbs", "2000": "sugar",
             "1004": "fat", "1258": "sat_fat", "1079": "fiber", "1093": "sodium_mg"}
SKIP_CATEGORIES = {"Baby Foods"}

def rows(name):
    with open(os.path.join(src, name), newline="", encoding="utf-8") as f:
        yield from csv.DictReader(f)

categories = {r["id"]: r["description"] for r in rows("food_category.csv")}
units = {r["id"]: r["name"] for r in rows("measure_unit.csv")}
foods = {}
for r in rows("food.csv"):
    cat = categories.get(r["food_category_id"], "")
    if cat in SKIP_CATEGORIES:
        continue
    foods[r["fdc_id"]] = {"name": r["description"].strip(), "category": cat}

values = defaultdict(dict)
for r in rows("food_nutrient.csv"):
    key = NUTRIENTS.get(r["nutrient_id"])
    if key and r["fdc_id"] in foods and r["amount"] != "":
        values[r["fdc_id"]][key] = float(r["amount"])

portions = defaultdict(list)
for r in rows("food_portion.csv"):
    if r["fdc_id"] not in foods or not r["gram_weight"]:
        continue
    grams = float(r["gram_weight"])
    if grams <= 0:
        continue
    amount = r["amount"].rstrip("0").rstrip(".") if r["amount"] else ""
    unit = units.get(r["measure_unit_id"], "")
    unit = "" if unit == "undetermined" else unit
    parts = [p for p in [amount, unit, r["modifier"] or r["portion_description"]] if p]
    label = " ".join(parts).replace(" ,", ",").strip()
    if label:
        portions[r["fdc_id"]].append({"n": label[:60], "g": round(grams, 1)})

if os.path.exists(out):
    os.remove(out)
db = sqlite3.connect(out)
db.executescript("""
CREATE TABLE foods(id INTEGER PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL,
  kcal REAL, protein REAL, carbs REAL, sugar REAL, fat REAL, sat_fat REAL, fiber REAL, salt REAL,
  portions TEXT NOT NULL);
CREATE VIRTUAL TABLE foods_fts USING fts4(name, tokenize=unicode61);
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
""")
n = 0
for fid, f in foods.items():
    v = values.get(fid, {})
    kcal = v.get("kcal")
    if kcal is None and all(k in v for k in ("protein", "carbs", "fat")):
        kcal = round(v["protein"] * 4 + v["carbs"] * 4 + v["fat"] * 9, 1)
    if kcal is None:
        continue
    salt = round(v["sodium_mg"] * 2.5 / 1000, 3) if "sodium_mg" in v else None
    db.execute("INSERT INTO foods VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", (
        int(fid), f["name"], f["category"], kcal, v.get("protein"), v.get("carbs"), v.get("sugar"),
        v.get("fat"), v.get("sat_fat"), v.get("fiber"), salt, json.dumps(portions.get(fid, [])[:6], separators=(",", ":"))))
    db.execute("INSERT INTO foods_fts(docid, name) VALUES(?,?)", (int(fid), f["name"]))
    n += 1
db.execute("INSERT INTO meta VALUES('version','sr_legacy_2018_04_v1')")
db.execute("INSERT INTO meta VALUES('source','USDA FoodData Central SR Legacy (public domain)')")
db.commit()
db.execute("INSERT INTO foods_fts(foods_fts) VALUES('optimize')")
db.commit()
db.execute("VACUUM")
db.close()
print(f"{n} foods -> {out} ({os.path.getsize(out)//1024} KiB)")
