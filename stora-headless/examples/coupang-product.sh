#!/usr/bin/env bash
# Open Coupang, find a product with JavaScript, click it, wait for the product page, read it, close.
#
# Needs a running stora-headless server, curl and jq.
#   STORA_HEADLESS_URL    server URL (default http://127.0.0.1:8090)
#   STORA_HEADLESS_TOKEN  server token, if the server uses one
#   PROXY                 optional proxy, e.g. http://USER:PASS@HOST:PORT (percent-encode special characters)
#   START_URL             page to start from (default https://www.coupang.com/)
#
# Usage: PROXY='http://user:pass@host:port' ./coupang-product.sh [product-page.html]
set -euo pipefail

BASE=${STORA_HEADLESS_URL:-http://127.0.0.1:8090}
START_URL=${START_URL:-https://www.coupang.com/}
HTML_OUT=${1:-}

call() { # call METHOD PATH [JSON]
  curl -sS -X "$1" "$BASE$2" \
    -H "Authorization: Bearer ${STORA_HEADLESS_TOKEN:-}" \
    -H 'Content-Type: application/json' \
    ${3:+-d "$3"}
}

# JavaScript run inside the page. Plain ES2015 (no async/await or class; see README).
FIND_PRODUCTS='Array.from(new Set(Array.from(document.querySelectorAll("a[href*=\"/vp/products/\"]"))
  .map(function (a) { return a.href.split("?")[0]; })))'

PRODUCT_INFO='(function () {
  function meta(p) { var m = document.querySelector("meta[property=\"" + p + "\"]"); return m ? m.content : null; }
  function text(sel) { var e = document.querySelector(sel); return e ? e.textContent.trim().replace(/\s+/g, " ") : null; }
  return {
    url: location.href.split("?")[0],
    title: document.title,
    blocked: !!document.getElementById("sec-if-cpt-container") || document.title === "Access Denied",
    name: meta("og:title") || text("h1"),
    image: meta("og:image"),
    price: text(".total-price strong") || text(".final-price-amount") || text(".price-amount")
  };
})()'

# 1. Open the start page in a new session
OPEN=$(jq -n --arg url "$START_URL" --arg proxy "${PROXY:-}" '{
  url: $url,
  acceptLanguage: "ko-KR,ko;q=0.9,en-US;q=0.8",
  jsWaitMs: 1000,
  return: []
} + (if $proxy == "" then {} else {proxy: $proxy} end)')
SESSION=$(call POST /sessions "$OPEN")
ID=$(jq -r '.id // empty' <<<"$SESSION")
if [ -z "$ID" ]; then
  echo "Could not open session: $(jq -c '{error, kind}' <<<"$SESSION")" >&2
  exit 1
fi
trap 'call DELETE "/sessions/$ID" >/dev/null' EXIT
echo "opened:  $(jq -c '{status, title, timingMs}' <<<"$SESSION")"

# 2. Find products with JavaScript
LINKS=$(call POST "/sessions/$ID/evaluate" "$(jq -n --arg s "$FIND_PRODUCTS" '{script: $s}')")
echo "found:   $(jq -c '.result // . | if type == "array" then {count: length, first: .[0]} else . end' <<<"$LINKS")"
if [ "$(jq '.result | length' <<<"$LINKS")" = "0" ]; then
  echo "No product links on $START_URL" >&2
  exit 1
fi

# 3. Click the first product, wait for the product page, read it
CLICK=$(call POST "/sessions/$ID/click" "$(jq -n --arg then "$PRODUCT_INFO" '{
  selector: "a[href*=\"/vp/products/\"]",
  jsWaitMs: 3000,
  then: $then,
  return: ["html"]
}')")
echo "clicked: $(jq -c '{navigated, status, timingMs, error, kind}' <<<"$CLICK")"
echo "product: $(jq -c '.result' <<<"$CLICK")"

if [ -n "$HTML_OUT" ]; then
  jq -r '.html // ""' <<<"$CLICK" >"$HTML_OUT"
  echo "html:    saved to $HTML_OUT ($(wc -c <"$HTML_OUT" | tr -d ' ') bytes)"
fi

if [ "$(jq '.result.blocked' <<<"$CLICK")" = "true" ]; then
  echo "note:    Coupang answered with its bot-protection page; product data is not available." >&2
fi
# 4. The session is closed by the EXIT trap.
