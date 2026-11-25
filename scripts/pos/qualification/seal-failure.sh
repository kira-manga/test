#!/usr/bin/env bash
set -euo pipefail
umask 077
stage="$RUNNER_TEMP/pos-qualification"
cd "$stage"
printf '%s' "$DIAGNOSTIC_CERTIFICATE" | base64 --decode | openssl x509 -inform DER -out "$stage/recipient.pem"
files=()
for pick in installed-compile.raw installed-compile-repository-failure.raw installed-cashier.raw installed-cashier-device.log seed.raw restart.raw profile-compile.raw profile-compile-repository-failure.raw recovery.raw companion.raw catalog-deposit-draft.raw payment-regression.raw payment-regression-repository-failure.raw retained-reader.raw cold-payment.raw opening-control.raw opening-control.xml history-source-control.raw history-source-control-repository-failure.raw tcp.raw tcp-runtime/process.log \
  tcp-runtime/master.log tcp-runtime/client1.log tcp-runtime/client2.log \
  tcp-runtime/compile.log tcp-runtime/compile-retry.log profile-*.txt; do
  if [[ -f "$pick" && ! -L "$pick" ]]; then files+=("$pick"); fi
done
if (( ${#files[@]} > 0 )); then
  tar -czf - -- "${files[@]}" |
    openssl cms -encrypt -binary -aes256 -outform DER -out "$stage/failure.cms" "$stage/recipient.pem"
fi
