#!/usr/bin/env bash
# bash rename.sh           -> preview
# bash rename.sh --apply   -> eksekusi
set -euo pipefail
APPLY=0; [[ "${1:-}" == "--apply" ]] && APPLY=1
cd "$(git rev-parse --show-toplevel)"

export REPO="https://github.com/SydForum/MusicPlay"
export TG="https://t.me/syidd_1"
export QRIS="https://i.ibb.co.com/M5Bs20S9/qr-ID1025412063568-31-08-26-1788184448-1788184449100.jpg"

TMP="$(mktemp -d)"
BR="$TMP/brand.pl"; PL="$TMP/full.pl"; MD="$TMP/readme.pl"

# ---- aturan branding (case-preserving, MetrolistGroup dilindungi) ----
cat > "$BR" <<'EOF'
s{(metrolist ?group)}{"\x01".unpack("H*",$1)."\x02"}gie;
s{(metrolist)}{ $1 eq uc($1) ? "MUSICPLAYER" : ($1 =~ /^[A-Z]/ ? "MusicPlayer" : "musicplayer") }gie;
s{\x01([0-9a-f]+)\x02}{pack("H*",$1)}ge;
EOF

# ---- aturan lengkap untuk isi file ----
cat > "$PL" <<'EOF'
my ($repo,$qris,$tg) = @ENV{qw(REPO QRIS TG)};
# repo upstream -> repo kamu (dependency lain tetap aman)
s{MetrolistGroup/Metrolist(?![\w-])}{SydForum/MusicPlay}gi;
# link
s{https?://(?:www\.)?metrolist\.cc[^\s)"'<>]*}{$repo}gi;
s{https?://(?:www\.)?(?:dsc\.gg|discord\.gg)/[^\s)"'<>]*}{$repo}gi;
s{https?://t\.me/metrolistapp}{$tg}gi;
s{https?://hosted\.weblate\.org/[^\s)"'<>]*}{$repo}gi;
s{https?://(?:www\.)?buymeacoffee\.com/[^\s)"'<>]*}{$qris}gi;
# package name (urutan penting)
s{com\.metrolist\.music(?![A-Za-z0-9_])}{com.sydforum.musicplay}g;
s{com\.metrolist(?![A-Za-z0-9_])}{com.sydforum.musicplay}g;
s{com/metrolist/music(?![A-Za-z0-9_])}{com/sydforum/musicplay}g;
s{com/metrolist(?![A-Za-z0-9_])}{com/sydforum/musicplay}g;
s{com_metrolist_music(?![A-Za-z0-9])}{com_sydforum_musicplay}g;
s{com_metrolist(?![A-Za-z0-9])}{com_sydforum_musicplay}g;
# Mo Agamy -> SydForum (baris copyright/SPDX dilewati)
unless (/copyright|©|\(c\)|spdx/i) {
  s{github\.com/mostafaalagamy}{github.com/SydForum}gi;
  s{Mo\s+Agamy|Mostafa\s*Al-?agamy|mostafaalagamy}{SydForum}gi;
}
EOF
cat "$BR" >> "$PL"

# ---- khusus README.md (blok multi-baris) ----
cat > "$MD" <<'EOF'
s{\[!\[Discord\]\([^)]*\)\]\([^)]*\)\s*}{}g;
s{ · \[\*\*Translate\*\*\]\(#translations\)}{}g;
s{# Translations\n.*?(?=# Support the Project)}{}s;
s{# Support the Project\n.*?(?=# Special Thanks)}{# Support the Project\n\n### Dukung pengembangan MusicPlayer\n\n

![QRIS]($ENV{QRIS})

\n\n---\n\n}s;
EOF

echo "== 1. Isi file =="
FILES="$TMP/files"
git ls-files -z -- . ':(exclude)LICENSE' \
  | xargs -0 grep -IliZE -- 'metrolist|agamy|alagamy|buymeacoffee|dsc\.gg|discord\.gg|weblate|t\.me/' > "$FILES" || true
echo "File yang akan diubah: $(tr -cd '\0' < "$FILES" | wc -c)"
if (( APPLY )); then
  [[ -f README.md ]] && perl -0pi "$MD" README.md
  xargs -r -0 -a "$FILES" perl -pi "$PL"
  if [[ -f README.md ]] && ! grep -q '^## Credits' README.md; then
    cat >> README.md <<'EOF2'

---

## Credits

MusicPlayer is a modified version of [Metrolist](https://github.com/MetrolistGroup/Metrolist) by Mo Agamy and contributors.
EOF2
  fi
  echo "Isi file sudah diubah."
fi

echo
echo "== 2. Folder package (com/metrolist -> com/sydforum/musicplay) =="
mapfile -d '' PK < <(find . -type d -path '*/com/metrolist' -not -path './.git/*' -print0)
for src in "${PK[@]}"; do
  dst="$(dirname "$src")/sydforum/musicplay"
  echo "$src  ->  $dst"
  if (( APPLY )); then
    mkdir -p "$dst"
    if [[ -d "$src/music" ]]; then
      find "$src/music" -mindepth 1 -maxdepth 1 -exec mv -t "$dst" {} +
      rmdir "$src/music"
    fi
    find "$src" -mindepth 1 -maxdepth 1 -exec mv -t "$dst" {} +
    rmdir "$src"
  fi
done

echo
echo "== 3. Nama file & folder lain =="
mapfile -d '' PATHS < <(find . -depth \( -path ./.git -o -path './.git/*' \) -prune -o -iname '*metrolist*' -print0)
for p in "${PATHS[@]}"; do
  [[ -e "$p" ]] || continue
  dir="$(dirname "$p")"; base="$(basename "$p")"
  new="$(printf '%s' "$base" | perl -p "$BR")"
  [[ "$new" == "$base" ]] && continue
  echo "$p  ->  $dir/$new"
  if (( APPLY )); then
    if [[ -e "$dir/$new" ]]; then echo "  !! sudah ada, dilewati"; else mv -- "$p" "$dir/$new"; fi
  fi
done

echo
echo "== 4. Sengaja dibiarkan (dependency / lisensi) =="
git grep -nIi 'metrolistgroup\|metrolist group' | head -20 || true

(( APPLY )) || echo -e "\nIni preview. Jalankan: bash rename.sh --apply"
