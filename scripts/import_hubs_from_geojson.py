#!/usr/bin/env python3
"""Import hub GeoJSON through the managed catalog API, never directly into PostgreSQL."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import urllib.error
import urllib.request
import uuid
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlparse

DEFAULT_INPUT = "data/hub-boundaries/jinan-malls.geojson"
IMPORT_PATH = "/admin/sync/catalog/import/hubs"
MAX_HUBS = 100
MAX_BODY_BYTES = 1_000_000
NAMESPACE = uuid.UUID("b1a6ae92-3c88-4f0b-b781-e5b7b3d68e12")

PI = 3.1415926535897932384626
A = 6378245.0
EE = 0.00669342162296594323


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default=DEFAULT_INPUT, help="Input GeoJSON path")
    parser.add_argument("--api-url", default=os.getenv("EATWHAT_ADMIN_API_URL"),
                        help="Full managed import endpoint URL; may also be set in EATWHAT_ADMIN_API_URL")
    parser.add_argument("--source-key", default="jinan-malls", help="Stable identity for this source dataset")
    parser.add_argument("--request-id", help="UUID reused for retries; defaults to a deterministic id for this content")
    parser.add_argument("--associate-restaurants", action="store_true",
                        help="Ask the campus catalog domain to compute covered restaurant assignments")
    parser.add_argument("--dry-run", action="store_true", help="Validate and print a redacted request summary")
    # Keep the old option recognizable, but refuse it explicitly so automation cannot silently invoke psql.
    parser.add_argument("--execute", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--sql-output", help=argparse.SUPPRESS)
    return parser.parse_args()


def out_of_china(lng: float, lat: float) -> bool:
    return (lng < 72.004 or lng > 137.8347) or (lat < 0.8293 or lat > 55.8271)


def transform_lat(lng: float, lat: float) -> float:
    ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat + 0.1 * lng * lat + 0.2 * math.sqrt(abs(lng))
    ret += (20.0 * math.sin(6.0 * lng * PI) + 20.0 * math.sin(2.0 * lng * PI)) * 2.0 / 3.0
    ret += (20.0 * math.sin(lat * PI) + 40.0 * math.sin(lat / 3.0 * PI)) * 2.0 / 3.0
    ret += (160.0 * math.sin(lat / 12.0 * PI) + 320 * math.sin(lat * PI / 30.0)) * 2.0 / 3.0
    return ret


def transform_lng(lng: float, lat: float) -> float:
    ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng + 0.1 * lng * lat + 0.1 * math.sqrt(abs(lng))
    ret += (20.0 * math.sin(6.0 * lng * PI) + 20.0 * math.sin(2.0 * lng * PI)) * 2.0 / 3.0
    ret += (20.0 * math.sin(lng * PI) + 40.0 * math.sin(lng / 3.0 * PI)) * 2.0 / 3.0
    ret += (150.0 * math.sin(lng / 12.0 * PI) + 300.0 * math.sin(lng / 30.0 * PI)) * 2.0 / 3.0
    return ret


def wgs84_to_gcj02(lng: float, lat: float) -> tuple[float, float]:
    if out_of_china(lng, lat):
        return lng, lat
    d_lat = transform_lat(lng - 105.0, lat - 35.0)
    d_lng = transform_lng(lng - 105.0, lat - 35.0)
    rad_lat = lat / 180.0 * PI
    magic = 1 - EE * math.sin(rad_lat) ** 2
    sqrt_magic = math.sqrt(magic)
    d_lat = d_lat * 180.0 / ((A * (1 - EE)) / (magic * sqrt_magic) * PI)
    d_lng = d_lng * 180.0 / (A / sqrt_magic * math.cos(rad_lat) * PI)
    return lng + d_lng, lat + d_lat


def iter_outer_ring(geometry: dict) -> list[list[float]]:
    if geometry["type"] == "Polygon":
        return geometry["coordinates"][0]
    if geometry["type"] == "MultiPolygon":
        return [point for polygon in geometry["coordinates"] for point in polygon[0]]
    raise ValueError(f"Unsupported geometry type: {geometry.get('type')}")


def compute_centroid(geometry: dict) -> tuple[float, float]:
    points = iter_outer_ring(geometry)
    if not points:
        raise ValueError("Geometry has no points")
    unique_points = points[:-1] if len(points) > 1 and points[0] == points[-1] else points
    return (sum(point[0] for point in unique_points) / len(unique_points),
            sum(point[1] for point in unique_points) / len(unique_points))


def feature_to_hub(feature: dict, index: int, associate_restaurants: bool) -> dict:
    geometry = feature.get("geometry")
    properties = feature.get("properties") or {}
    if not isinstance(geometry, dict):
        raise ValueError(f"Feature {index} has no geometry")
    name = properties.get("displayName") or properties.get("name")
    if not isinstance(name, str) or not name.strip():
        raise ValueError(f"Feature {index} is missing a name")
    center = properties.get("centroid")
    if center:
        lng, lat = float(center["lon"]), float(center["lat"])
    else:
        lng, lat = compute_centroid(geometry)
    gcj_lng, gcj_lat = wgs84_to_gcj02(lng, lat)
    source_id = feature.get("id") or properties.get("sourceId") or properties.get("id") or str(index)
    return {
        "sourceId": str(source_id),
        "name": name.strip(),
        "centerWgs": {"lng": lng, "lat": lat},
        "centerGcj": {"lng": gcj_lng, "lat": gcj_lat},
        "boundaryGeoJson": canonical_json(geometry).decode("utf-8"),
        "rebindCoveredRestaurants": associate_restaurants,
    }


def canonical_json(value: object) -> bytes:
    def render(item: object) -> str:
        if item is None:
            return "null"
        if item is True:
            return "true"
        if item is False:
            return "false"
        if isinstance(item, str):
            return json.dumps(item, ensure_ascii=False, separators=(",", ":"))
        if isinstance(item, int):
            return str(item)
        if isinstance(item, float):
            if not math.isfinite(item):
                raise ValueError("Non-finite JSON number")
            number = Decimal(str(item)).normalize()
            if number == 0:
                return "0"
            return format(number, "f")
        if isinstance(item, list):
            return "[" + ",".join(render(value) for value in item) + "]"
        if isinstance(item, dict):
            return "{" + ",".join(
                render(str(key)) + ":" + render(item[key]) for key in sorted(item)
            ) + "}"
        raise TypeError(f"Unsupported JSON type: {type(item).__name__}")

    return render(value).encode("utf-8")


def load_payload(path: Path, source_key: str, request_id: str | None, associate: bool) -> tuple[dict, str]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    features = raw.get("features", []) if isinstance(raw, dict) else []
    if not features:
        raise ValueError(f"No features found in {path}")
    if len(features) > MAX_HUBS:
        raise ValueError(f"This import contains {len(features)} hubs; split it into batches of at most {MAX_HUBS}")
    hubs = [feature_to_hub(feature, i, associate) for i, feature in enumerate(features)]
    content_hash = hashlib.sha256(canonical_json(hubs)).hexdigest()
    if request_id:
        resolved_id = str(uuid.UUID(request_id))
    else:
        resolved_id = str(uuid.uuid5(NAMESPACE, f"{source_key}:{content_hash}"))
    payload = {"sourceKey": source_key, "requestId": resolved_id,
               "contentHash": content_hash, "hubs": hubs}
    encoded = canonical_json(payload)
    if len(encoded) > MAX_BODY_BYTES:
        raise ValueError(f"Request body is {len(encoded)} bytes; limit is {MAX_BODY_BYTES}")
    return payload, content_hash


def post_import(api_url: str, token: str, payload: dict) -> dict:
    parsed = urlparse(api_url)
    if parsed.scheme != "https" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise ValueError("The import API must use HTTPS outside localhost")
    request = urllib.request.Request(api_url, data=canonical_json(payload), method="POST", headers={
        "Authorization": f"Bearer {token}", "Content-Type": "application/json", "Accept": "application/json",
    })
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read(MAX_BODY_BYTES + 1)
            if len(body) > MAX_BODY_BYTES:
                raise ValueError("Server response exceeded the configured response limit")
            result = json.loads(body.decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"Import API returned HTTP {exc.code}") from None
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Could not reach the import API: {exc.reason}") from None
    if not isinstance(result, dict) or result.get("code") != 0:
        code = result.get("code") if isinstance(result, dict) else "invalid response"
        raise RuntimeError(f"Import was rejected by the application (code={code})")
    return result


def main() -> int:
    args = parse_args()
    if args.execute or args.sql_output:
        print("Direct SQL/psql import is disabled; use the managed catalog HTTP endpoint.", file=sys.stderr)
        return 2
    try:
        if not args.source_key.strip():
            raise ValueError("--source-key cannot be blank")
        payload, content_hash = load_payload(Path(args.input), args.source_key.strip(), args.request_id,
                                             args.associate_restaurants)
        if args.dry_run:
            print(f"Validated {len(payload['hubs'])} hubs; contentHash={content_hash}; requestId={payload['requestId']}")
            return 0
        if not args.api_url:
            raise ValueError("Pass --api-url or set EATWHAT_ADMIN_API_URL")
        token = os.getenv("EATWHAT_ADMIN_TOKEN")
        if not token:
            raise ValueError("Set EATWHAT_ADMIN_TOKEN in the environment; credentials are never read from CLI arguments")
        response = post_import(args.api_url, token, payload)
        print(f"Import accepted: {json.dumps(response.get('data'), ensure_ascii=False, separators=(',', ':'))}")
        return 0
    except Exception as exc:
        print(f"Hub import failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
