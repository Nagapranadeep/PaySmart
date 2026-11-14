#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build.sh
java -cp work/classes com.paysmart.bank.BankingDemo "$@"
