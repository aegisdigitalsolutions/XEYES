package com.rfmapper.radio.android

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.rfmapper.core.model.IdentifierType
import com.rfmapper.core.model.MetadataKeys
import com.rfmapper.core.model.RadioIdentifierNormalizer
import com.rfmapper.core.model.ResultFreshness
import com.rfmapper.core.model.SensorType
import com.rfmapper.core.radio.BleObservationProvider
import com.rfmapper.core.radio.Capability
import com.rfmapper.core.radio.Clock
import com.rfmapper.core.radio.RadioSample
import com.rfmapper.core.radio.ScanProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * BLE scanning on Android.
 *
 * Duty-cycled rather than continuous outside `AGGRESSIVE`, because a continuous LE scan is the
 * single largest battery cost in the app and a site's BLE population does not change meaningfully
 * between one eight-second window and the next.
 *
 * A locally-administered (rotating) address is typed as `BLE_MAC_RANDOM` at the moment of capture.
 * That type is what stops such an identifier from ever being attributed to a device automatically:
 * the distinction has to be made here, where the address bits are visible, rather than inferred
 * downstream (`docs/17-identity-and-attribution-policy.md`).
 */
class AndroidBleObservationProvider(
    private val context: Context,
    private val profile: ScanProfile = ScanProfile.DEFAULT,
    private val clock: Clock = AndroidClock,
    /**
     * Hardware-level filters. Left empty by default: the site's environmental RF context is
     * evidence in its own right, and filtering to enrolled devices only would discard the anchor
     * sightings that positioning depends on.
     */
    private val filters: List<ScanFilter> = emptyList(),
) : BleObservationProvider {

    override suspend fun capability(): Capability = RadioPermissions.bleCapability(context)

    @SuppressLint("MissingPermission")
    override fun samples(): Flow<RadioSample> = callbackFlow {
        val scanner = scanner()
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { toSample(it)?.let(::trySend) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { result -> toSample(result)?.let(::trySend) }
            }

            override fun onScanFailed(errorCode: Int) {
                // Not fatal to the session: Wi-Fi collection continues, and the failure is
                // surfaced through the capability the dashboard polls.
                close(BleScanException(errorCode))
            }
        }

        val settings = scanSettings()

        val duty = launch {
            if (profile.bleIsContinuous) {
                startScan(scanner, callback, settings)
                // Held open until the flow is cancelled.
                while (isActive) delay(DUTY_CHECK_MILLIS)
            } else {
                while (isActive) {
                    startScan(scanner, callback, settings)
                    delay(profile.bleWindowMillis)
                    stopScan(scanner, callback)
                    delay(profile.bleIdleMillis)
                }
            }
        }

        awaitClose {
            duty.cancel()
            stopScan(scanner, callback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(
        scanner: BluetoothLeScanner,
        callback: ScanCallback,
        settings: ScanSettings,
    ) {
        runCatching { scanner.startScan(filters, settings, callback) }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(scanner: BluetoothLeScanner, callback: ScanCallback) {
        runCatching { scanner.stopScan(callback) }
    }

    private fun scanner(): BluetoothLeScanner? {
        if (!RadioPermissions.bleCapability(context).live) return null
        return RadioPermissions.bluetoothAdapter(context)?.bluetoothLeScanner
    }

    private fun scanSettings(): ScanSettings = ScanSettings.Builder()
        .setScanMode(
            when (profile) {
                ScanProfile.AGGRESSIVE -> ScanSettings.SCAN_MODE_LOW_LATENCY
                ScanProfile.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                ScanProfile.ENDURANCE -> ScanSettings.SCAN_MODE_LOW_POWER
            },
        )
        // Every advertisement, not just first-seen: RSSI over time is the measurement, and
        // CALLBACK_TYPE_FIRST_MATCH would deliver one sample per device per scan.
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setLegacy(false)
                setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
        }
        .build()

    private fun toSample(result: ScanResult): RadioSample? {
        val address = RadioIdentifierNormalizer.normalizeMac(result.device?.address) ?: return null
        val identifierType = RadioIdentifierNormalizer.identifierTypeForBleAddress(address)

        // `timestampNanos` is on the monotonic clock, so the wall-clock instant is derived from the
        // sample's own age rather than from the moment the callback happened to run.
        val resultMonotonicMillis = result.timestampNanos / 1_000_000
        val nowMonotonic = clock.monotonicElapsedMillis()
        val age = (nowMonotonic - resultMonotonicMillis).coerceIn(0, MAX_PLAUSIBLE_AGE_MILLIS)
        val wallMillis = (clock.wallClockMillis() - age).coerceAtLeast(1L)

        val record = result.scanRecord
        val metadata = buildMap {
            put(MetadataKeys.IDENTIFIER_SCOPE, SCOPE_GLOBAL)
            record?.deviceName?.takeIf { it.isNotBlank() }
                ?.let { put(MetadataKeys.BLE_DEVICE_NAME, it) }
            serviceUuids(record)?.let { put(MetadataKeys.BLE_SERVICE_UUIDS, it) }
            serviceData(record)?.let { put(MetadataKeys.BLE_SERVICE_DATA, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                put(MetadataKeys.BLE_IS_LEGACY, result.isLegacy.toString())
                put(MetadataKeys.BLE_PRIMARY_PHY, result.primaryPhy.toString())
            }
            put(MetadataKeys.BLE_IS_CONNECTABLE, result.isConnectableCompat().toString())
        }

        return RadioSample(
            sensorType = SensorType.BLE,
            identifier = address,
            identifierType = identifierType,
            wallClockMillis = wallMillis,
            monotonicElapsedMillis = resultMonotonicMillis,
            rssi = result.rssi,
            txPower = txPower(result, record),
            bleServiceUuid = primaryServiceUuid(record),
            manufacturerData = manufacturerData(record),
            freshness = ResultFreshness.FRESH,
            resultAgeMillis = age,
            metadata = metadata,
        )
    }

    private fun ScanResult.isConnectableCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isConnectable else true

    private fun txPower(result: ScanResult, record: ScanRecord?): Int? {
        val advertised = record?.txPowerLevel
        if (advertised != null && advertised != Int.MIN_VALUE && advertised in -127..127) {
            return advertised
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val reported = result.txPower
            if (reported != ScanResult.TX_POWER_NOT_PRESENT && reported in -127..127) return reported
        }
        return null
    }

    private fun primaryServiceUuid(record: ScanRecord?): String? =
        record?.serviceUuids?.firstOrNull()?.uuid?.toString()
            ?.let(RadioIdentifierNormalizer::normalizeUuid)

    private fun serviceUuids(record: ScanRecord?): String? =
        record?.serviceUuids
            ?.mapNotNull { RadioIdentifierNormalizer.normalizeUuid(it.uuid.toString()) }
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(";")

    private fun serviceData(record: ScanRecord?): String? =
        record?.serviceData
            ?.mapNotNull { (uuid, bytes) ->
                val key = RadioIdentifierNormalizer.normalizeUuid(uuid.uuid.toString()) ?: return@mapNotNull null
                val value = RadioIdentifierNormalizer.normalizeHex(bytes.toHex()) ?: return@mapNotNull null
                "$key=$value"
            }
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(";")

    /**
     * Manufacturer-specific data, company id first.
     *
     * A stable manufacturer payload is often the only durable thing about a device whose address
     * rotates — an iBeacon's UUID, for example — which makes it the identifier an administrator can
     * actually enrol.
     */
    private fun manufacturerData(record: ScanRecord?): String? {
        val data = record?.manufacturerSpecificData ?: return null
        if (data.size() == 0) return null
        val companyId = data.keyAt(0)
        val payload = data.valueAt(0) ?: return null
        return RadioIdentifierNormalizer.encodeManufacturerData(companyId, payload)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    /** True when this sample's identifier is a rotating address and must never be attributed. */
    fun isEphemeral(sample: RadioSample): Boolean =
        sample.identifierType == IdentifierType.BLE_MAC_RANDOM

    private companion object {
        const val SCOPE_GLOBAL = "GLOBAL"
        const val DUTY_CHECK_MILLIS = 1_000L

        /** A sample cannot plausibly be older than this; beyond it the clocks disagree. */
        const val MAX_PLAUSIBLE_AGE_MILLIS = 60_000L
    }
}

class BleScanException(val errorCode: Int) : Exception("BLE scan failed with code $errorCode")
