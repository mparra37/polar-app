package com.polar.polarsdkecghrdemo

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.StepMode
import com.androidplot.xy.XYPlot
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.DeviceStreamingFeature
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl.defaultImplementation
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarEcgData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.util.*

class ECGActivity : AppCompatActivity(), PlotterListener {
    companion object {
        private const val TAG = "ECGActivity"
    }

    private lateinit var api: PolarBleApi
    private lateinit var textViewHR: TextView
    private lateinit var textViewRR: TextView
    private lateinit var textViewDeviceId: TextView
    private lateinit var textViewBattery: TextView
    private lateinit var textViewFwVersion: TextView
    private lateinit var plot: XYPlot
    private lateinit var ecgPlotter: EcgPlotter
    private var ecgDisposable: Disposable? = null
    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecg)
        deviceId = intent.getStringExtra("id") ?: throw Exception("ECGActivity couldn't be created, no deviceId given")
        textViewHR = findViewById(R.id.hr)
        textViewRR = findViewById(R.id.rr)
        textViewDeviceId = findViewById(R.id.deviceId)
        textViewBattery = findViewById(R.id.battery_level)
        textViewFwVersion = findViewById(R.id.fw_version)
        plot = findViewById(R.id.plot)

        api = defaultImplementation(
            applicationContext,
            PolarBleApi.FEATURE_POLAR_SENSOR_STREAMING or
                    PolarBleApi.FEATURE_BATTERY_INFO or
                    PolarBleApi.FEATURE_DEVICE_INFO or
                    PolarBleApi.FEATURE_HR
        )
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(blePowerState: Boolean) {
                Log.d(TAG, "BluetoothStateChanged $blePowerState")
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connected " + polarDeviceInfo.deviceId)
                Toast.makeText(applicationContext, R.string.connected, Toast.LENGTH_SHORT).show()
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connecting ${polarDeviceInfo.deviceId}")
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device disconnected ${polarDeviceInfo.deviceId}")
            }

            override fun streamingFeaturesReady(identifier: String, features: Set<DeviceStreamingFeature>) {
                for (feature in features) {
                    Log.d(TAG, "Streaming feature is ready: $feature")
                    when (feature) {
                        DeviceStreamingFeature.ECG -> streamECG()
                        else -> {}
                    }
                }
            }

            override fun hrFeatureReady(identifier: String) {
                Log.d(TAG, "HR Feature ready $identifier")
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                if (uuid == UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")) {
                    val msg = "Firmware: " + value.trim { it <= ' ' }
                    Log.d(TAG, "Firmware: " + identifier + " " + value.trim { it <= ' ' })
                    textViewFwVersion.append(msg.trimIndent())
                }
            }

            override fun batteryLevelReceived(identifier: String, batteryLevel: Int) {
                Log.d(TAG, "Battery level $identifier $batteryLevel%")
                val batteryLevelText = "Battery level: $batteryLevel%"
                textViewBattery.append(batteryLevelText)
            }

            override fun hrNotificationReceived(identifier: String, polarHrData: PolarHrData) {
                Log.d(TAG, "HR " + polarHrData.hr)
                if (polarHrData.rrsMs.isNotEmpty()) {
                    val rrText = "(${polarHrData.rrsMs.joinToString(separator = "ms, ")}ms)"
                    textViewRR.text = rrText
                }

                textViewHR.text = polarHrData.hr.toString()
            }

            override fun polarFtpFeatureReady(identifier: String) {
                Log.d(TAG, "Polar FTP ready $identifier")
            }
        })
        try {
            api.connectToDevice(deviceId)
        } catch (a: PolarInvalidArgument) {
            a.printStackTrace()
        }
        val deviceIdText = "ID: $deviceId"
        textViewDeviceId.text = deviceIdText

        ecgPlotter = EcgPlotter("ECG", 130)
        ecgPlotter.setListener(this)

        //plot.setMarkupEnabled(true);
        plot.addSeries(ecgPlotter.getSeries(), ecgPlotter.formatter)
        plot.setRangeBoundaries(-1.5, 1.5, BoundaryMode.FIXED)
        plot.setRangeStep(StepMode.INCREMENT_BY_FIT, 0.25)
        plot.setDomainStep(StepMode.INCREMENT_BY_VAL, 130.0)
        plot.setDomainBoundaries(0, 650, BoundaryMode.FIXED)
        plot.linesPerRangeLabel = 2
    }

    public override fun onDestroy() {
        super.onDestroy()
        ecgDisposable?.let {
            if (!it.isDisposed) it.dispose()
        }
        api.shutDown()
    }

    fun streamECG() {
        if (ecgDisposable == null) {
            ecgDisposable = api.requestStreamSettings(deviceId, DeviceStreamingFeature.ECG)
                .toFlowable()
                .flatMap { sensorSetting: PolarSensorSetting -> api.startEcgStreaming(deviceId, sensorSetting.maxSettings()) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { polarEcgData: PolarEcgData ->
                        Log.d(TAG, "ecg update")
                        for (data in polarEcgData.samples) {
                            ecgPlotter.sendSingleSample((data.toFloat() / 1000.0).toFloat())
                        }
                    },
                    { error: Throwable ->
                        Log.e(TAG, "Ecg stream failed $error")
                        ecgDisposable = null
                    },
                    {
                        Log.d(TAG, "Ecg stream complete")
                    }
                )
        } else {
            // NOTE stops streaming if it is "running"
            ecgDisposable?.dispose()
            ecgDisposable = null
        }
    }

    override fun update() {
        runOnUiThread { plot.redraw() }
    }
}