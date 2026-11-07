"""공공 실시간 주차 id와 시설정보(이름·입구 좌표)를 맞춰 src/parking_lots.json([[id,이름,위도,경도],...])을 만든다.
키는 server/.dev.vars의 PARKING_API_KEY(포털의 디코딩 키, 코드가 인코딩한다)에서 읽는다. 출력·예외에 URL·키를 내지 않는다.
실행: python server/scripts/parking_index.py  (시설정보 약 170만 행을 5000행씩 넘기므로 수 분 걸린다)"""
import json
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = "https://apis.data.go.kr/B553881/Parking_v1"
ROWS = 5000
root = Path(__file__).resolve().parent.parent


def read_key():
    try:
        for line in (root / ".dev.vars").read_text(encoding="utf-8").splitlines():
            k, _, v = line.partition("=")
            if k.strip() == "PARKING_API_KEY" and v.strip().strip("\"'"):
                return v.strip().strip("\"'")
    except OSError:
        pass
    sys.exit("server/.dev.vars에 PARKING_API_KEY=<디코딩 키> 를 넣고 다시 실행하세요.")


def get(key, path, page):
    for wait in (30, 60, 0):
        try:
            with urllib.request.urlopen(f"{BASE}/{path}?serviceKey={urllib.parse.quote(key, safe='')}&pageNo={page}&numOfRows={ROWS}&format=2", timeout=180) as r:
                return json.load(r)
        except Exception:  # 메시지에 URL·키가 들어 있을 수 있어 버린다
            time.sleep(wait)
    print(f"{path} {page}페이지 건너뜀(키·네트워크 확인).")
    return {}


def main():
    key = read_key()
    ids = {r["prk_center_id"] for r in get(key, "PrkRealtimeInfo_v1", 1).get("PrkRealtimeInfo", [])}
    print(f"실시간 {len(ids)}곳")
    found = {}
    out = root / "src" / "parking_lots.json"
    save = lambda: out.write_text(json.dumps(list(found.values()), ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    def take(j):
        for r in j.get("PrkSttusInfo", []):
            i = r.get("prk_center_id")
            if i in ids and i not in found:
                try:
                    found[i] = [i, r["prk_plce_nm"], float(r["prk_plce_entrc_la"]), float(r["prk_plce_entrc_lo"])]
                except (KeyError, ValueError, TypeError):
                    pass  # 좌표 없는 곳은 근처 검색에 쓸 수 없다

    first = get(key, "PrkSttusInfo_v1", 1)
    take(first)
    pages = -(-int(first.get("totalCount", 0)) // ROWS)
    print(f"시설 {pages}페이지")
    with ThreadPoolExecutor(4) as ex:  # 한 페이지 약 37초(실측)
        for n, j in enumerate(ex.map(lambda p: get(key, "PrkSttusInfo_v1", p), range(2, pages + 1)), 2):
            take(j)
            if n % 20 == 0:
                save()
                print(f"페이지 {n}/{pages} 일치 {len(found)}/{len(ids)}")
    save()
    print(f"저장 {len(found)}곳")


main()
