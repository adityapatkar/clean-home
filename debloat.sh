#!/bin/bash
# Disables Amazon ad, tracking, telemetry and promo packages on the Fire TV.
# Reversible: ./debloat.sh --restore re-enables everything in this list.
#
# Deliberately KEPT: AirPlay (com.apple.airplay.amazon.airplay), screen mirroring
# (com.amazon.cast.sink), phone casting (dialservice, awvflingreceiver, whisperplay),
# HDMI inputs (livetv, mediatek.*), Amazon's launcher (the Home redirect clicks a
# tile in it), Alexa voice, the Appstore (app updates) and system updates.
set -uo pipefail
# The TV comes from ANDROID_SERIAL or TV_ADDRESS in config.properties.
cd "$(dirname "$0")"
if [ -z "${ANDROID_SERIAL:-}" ] && [ -f config.properties ]; then
    ANDROID_SERIAL=$(grep -E '^TV_ADDRESS=' config.properties | cut -d= -f2-)
fi
export ANDROID_SERIAL="${ANDROID_SERIAL:?set TV_ADDRESS in config.properties or ANDROID_SERIAL}"

PACKAGES=(
    # Ads and viewing tracking
    com.amazon.tv.acr                   # automatic content recognition: identifies what you watch, incl. HDMI
    com.amazon.ftvads.deeplinking       # ad deep links
    com.amazon.hybridadidservice        # advertising ID
    com.amazon.media.recommendations    # sponsored/recommended rows
    com.amazon.sneakpeek                # autoplaying home-screen previews
    com.amazon.kso.blackbird            # "special offers" ads
    com.amazon.shoptv.client            # shopping
    com.amazon.shoptv.firetv.client     # shopping
    com.amazon.minitv.android.app       # miniTV
    # Telemetry
    com.amazon.device.logmanager
    com.amazon.device.crashmanager
    com.amazon.tv.fw.metrics
    com.amazon.wirelessmetrics.service
    com.amazon.dp.logger
    com.amazon.perfcollection
    # Promos, tutorials, demo content
    com.amazon.tv.easyupgrade
    com.amazon.storm.lightning.tutorial
    com.amazon.tmm.tutorial
    com.amazon.tv.releasenotes
    com.amazon.tv.democontent.provider
    com.amazon.bueller.music
    com.amazon.bueller.photos
    com.amazon.gamehub
    # Alexa pop-ups (voice search still works)
    com.amazon.tv.alexanotifications
    com.amazon.tv.alexaalerts
)

for p in "${PACKAGES[@]}"; do
    if [ "${1:-}" = "--restore" ]; then
        out=$(adb shell pm enable "$p" 2>&1 || adb shell cmd package install-existing "$p" 2>&1)
    else
        out=$(adb shell pm disable-user --user 0 "$p" 2>&1)
        case "$out" in *"new state: disabled"*) ;; *) out="$out | $(adb shell pm uninstall --user 0 "$p" 2>&1)";; esac
    fi
    printf '%-38s %s\n' "$p" "$(echo "$out" | tr '\n' ' ' | cut -c1-90)"
done
